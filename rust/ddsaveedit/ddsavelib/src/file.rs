use byteorder::{LittleEndian, ReadBytesExt};
use json_tools::{Buffer, BufferType, Lexer, Span, Token, TokenType};
use std::{
    convert::TryFrom,
    ffi::CStr,
    io::{Cursor, Read, Seek, Write},
};

use super::util::name_hash;

#[derive(Clone, Debug)]
pub struct File {
    h: Header,
    o: Objects,
    f: Fields,
    dat: Vec<Field>,
}

macro_rules! check_offset {
    ($rd:expr, $exp:expr) => {{
        let pos = $rd.stream_position()?;
        if pos != $exp as u64 {
            return Err(FromBinError::OffsetMismatch {
                exp: $exp as u64,
                is: pos,
            });
        }
    }};
}

macro_rules! indent {
    ($w:expr, $num:expr) => {{
        for _ in 0..$num {
            $w.write_all(b"    ")?;
        }
    }};
}

macro_rules! span {
    ($e:expr) => {
        if let Buffer::Span(s) = $e {
            s
        } else {
            unreachable!("buffer not span")
        }
    };
}

#[derive(Copy, Clone)]
struct FileStr<'a>(&'a str);

impl<'a> std::ops::Index<&Span> for FileStr<'a> {
    type Output = str;

    fn index(&self, index: &Span) -> &Self::Output {
        &self.0[index.first as usize..index.end as usize]
    }
}

#[derive(PartialEq, Eq)]
enum Exp {
    Field,
    Close,
    FieldOrClose,
}

#[rustfmt::skip]
macro_rules! something_comma_separated {
    ($lex:ident, $op:ident, $tok:ident, $d:stmt, $err:literal, $cls:ident) => {
        let mut exp = Exp::FieldOrClose;
        loop {
            let tok = $lex.peek().ok_or(FromJsonError::UnexpEOF)?;
            match tok.kind {
                TokenType::$tok if exp != Exp::Close => {$d},
                TokenType::$cls if exp != Exp::Field => break,
                _ => {
                    let span = span!(&tok.buf);
                    return Err(FromJsonError::Expected(
                        $err.to_owned() + " got: " + &format!("{:?}", tok.kind),
                        span.first,
                        span.end,
                    ));
                }
            }

            exp = if $lex.peek().ok_or(FromJsonError::UnexpEOF)?.kind == TokenType::Comma {
                let _ = $lex.next();
                Exp::Field
            } else {
                Exp::Close
            };
        }

        Self::expect($lex, TokenType::$cls, true)?;
    };
}

impl File {
    pub fn try_from_reader<R: Read + Seek>(reader: &'_ mut R) -> Result<Self, FromBinError> {
        let h = Header::try_from_reader(reader)?;
        check_offset!(reader, h.objects_offset);
        let o: Objects = Objects::try_from_reader(reader, h.objects_num)?;

        check_offset!(reader, h.fields_offset);
        let mut f = Fields::try_from_reader(reader, h.fields_num)?;

        check_offset!(reader, h.data_offset);
        let dat = decode_fields(reader, &mut f, &o, h.data_size)?;
        Ok(File { h, o, f, dat })
    }

    /// Decode a `File` from a `Read` representing a JSON stream.
    #[inline(never)]
    pub fn try_from_json<R: Read>(reader: &'_ mut R) -> Result<Self, FromJsonError> {
        let mut x = vec![];
        reader.read_to_end(&mut x).unwrap();
        let slic = FileStr(std::str::from_utf8(&x).unwrap());

        let mut lex = Lexer::new(slic.0.bytes(), BufferType::Span).peekable();
        Self::try_from_lexer(slic, &mut lex)
    }

    /// If `inner` is set, the parser knows not to expect an opening brace
    /// as the opening brace is expected to be already consumed by the outer
    /// file.
    fn try_from_lexer<T: Iterator<Item = Token>>(
        slic: FileStr,
        lex: &mut std::iter::Peekable<T>,
    ) -> Result<Self, FromJsonError> {
        let mut s = Self {
            h: Default::default(),
            o: Default::default(),
            f: Default::default(),
            dat: vec![],
        };

        Self::expect(lex, TokenType::CurlyOpen, true)?;
        let mut name_stack = vec![];

        s.read_child_fields(slic, &mut name_stack, lex)?;
        s.fixup_offsets();

        Ok(s)
    }

    fn read_child_fields<T: Iterator<Item = Token>>(
        &mut self,
        slic: FileStr,
        name_stack: &mut Vec<String>,
        lex: &mut std::iter::Peekable<T>,
    ) -> Result<Vec<FieldIdx>, FromJsonError> {
        let mut child_fields = vec![];

        something_comma_separated!(
            lex,
            CurlyOpen,
            String,
            {
                let tok = lex.next().unwrap();
                let span = span!(&tok.buf);
                let name = &slic.0[span.first as usize + 1..span.end as usize - 1];
                let idx = self.read_field(slic, name, None, name_stack, lex)?;
                child_fields.push(idx);
            },
            "expected name or }",
            CurlyClose
        );

        Ok(child_fields)
    }

    fn read_field<T: Iterator<Item = Token>>(
        &mut self,
        slic: FileStr,
        name: &str,
        parent: Option<ObjIdx>,
        name_stack: &mut Vec<String>,
        lex: &mut std::iter::Peekable<T>,
    ) -> Result<FieldIdx, FromJsonError> {
        let field_index = FieldIdx(self.f.fields.len());
        self.f.fields.push(FieldInfo {
            name_hash: name_hash(name),
            field_info: (((name.len() + 1) & 0b111111111) << 2) as u32,
            offset: 0,
        });
        // Identify type
        name_stack.push(name.to_owned());
        Self::expect(lex, TokenType::Colon, true)?;
        let val = match lex.peek().ok_or(FromJsonError::JsonErr)?.kind {
            TokenType::CurlyOpen => {
                if name == "raw_data" || name == "static_save" {
                    let inner = File::try_from_lexer(slic, lex)?;
                    Some(FieldType::File(Some(Box::new(inner))))
                } else {
                    lex.next();
                    self.dat.push(Field {
                        name: name.to_owned(),
                        parent,
                        tipe: FieldType::Object(vec![]),
                    });
                    let obj_index = ObjIdx(self.o.objs.len());
                    self.o.objs.push(ObjectInfo {
                        field: field_index,
                        parent,
                        num_direct_childs: 0,
                        num_all_childs: 0,
                    });
                    self.f.fields[field_index.0].field_info |= 1;
                    self.f.fields[field_index.0].field_info |=
                        ((obj_index.0 & 0b11111111111111111111) << 11) as u32;
                    let childs = self.read_child_fields(slic, name_stack, lex)?;
                    self.o.objs[obj_index.0].num_direct_childs = childs.len() as u32;
                    self.o.objs[obj_index.0].num_all_childs =
                        (self.f.fields.len() - field_index.0) as u32;
                    if let FieldType::Object(ref mut chl) = self.dat[field_index.0].tipe {
                        *chl = childs;
                    } else {
                        unreachable!("pushed obj earlier");
                    }
                    None
                }
            }
            _ => {
                if let Some(mut val) = hardcoded_type(&name_stack) {
                    match &mut val {
                        FieldType::Float(ref mut f) => {
                            let tok = Self::expect(lex, TokenType::Number, true)?;
                            let span = span!(&tok.buf);
                            *f = slic[span].parse::<f32>().map_err(|_| {
                                FromJsonError::LiteralFormat(
                                    "expected float".to_owned(),
                                    span.first,
                                    span.end,
                                )
                            })?;
                        }
                        FieldType::IntVector(ref mut v) => {
                            Self::expect(lex, TokenType::BracketOpen, true)?;
                            something_comma_separated!(
                                lex,
                                BracketOpen,
                                Number,
                                {
                                    let tok = lex.next().unwrap();
                                    let span = span!(&tok.buf);
                                    v.push(slic[span].parse::<i32>().map_err(|_| {
                                        FromJsonError::LiteralFormat(
                                            "expected int".to_owned(),
                                            span.first,
                                            span.end,
                                        )
                                    })?);
                                },
                                "expected number or ]",
                                BracketClose
                            );
                        }
                        FieldType::StringVector(ref mut v) => {
                            Self::expect(lex, TokenType::BracketOpen, true)?;
                            something_comma_separated!(
                                lex,
                                BracketOpen,
                                String,
                                {
                                    let tok = lex.next().unwrap();
                                    let span = span!(&tok.buf);
                                    let string = &slic[span];
                                    v.push(string[1..string.len() - 1].to_owned());
                                },
                                "expected string or ]",
                                BracketClose
                            );
                        }
                        FieldType::FloatArray(ref mut v) => {
                            Self::expect(lex, TokenType::BracketOpen, true)?;
                            something_comma_separated!(
                                lex,
                                BracketOpen,
                                Number,
                                {
                                    let tok = lex.next().unwrap();
                                    let span = span!(&tok.buf);
                                    v.push(slic[span].parse::<f32>().map_err(|_| {
                                        FromJsonError::LiteralFormat(
                                            "expected int".to_owned(),
                                            span.first,
                                            span.end,
                                        )
                                    })?);
                                },
                                "expected number or ]",
                                BracketClose
                            );
                        }
                        FieldType::TwoInt(ref mut i1, ref mut i2) => {
                            Self::expect(lex, TokenType::BracketOpen, true)?;
                            let span1 = &span!(Self::expect(lex, TokenType::Number, true)?.buf);
                            Self::expect(lex, TokenType::Comma, true)?;
                            let span2 = &span!(Self::expect(lex, TokenType::Number, true)?.buf);
                            Self::expect(lex, TokenType::BracketClose, true)?;

                            *i1 = slic[span1].parse::<i32>().map_err(|_| {
                                FromJsonError::LiteralFormat(
                                    "expected int".to_owned(),
                                    span1.first,
                                    span1.end,
                                )
                            })?;
                            *i2 = slic[span2].parse::<i32>().map_err(|_| {
                                FromJsonError::LiteralFormat(
                                    "expected int".to_owned(),
                                    span1.first,
                                    span1.end,
                                )
                            })?;
                        }
                        FieldType::Char(ref mut c) => {
                            let tok = Self::expect(lex, TokenType::String, true)?;
                            let span = span!(&tok.buf);
                            if span.end - span.first != 3 {
                                return Err(FromJsonError::LiteralFormat(
                                    "expect char with 1 character".to_owned(),
                                    span.first,
                                    span.end,
                                ));
                            }
                            *c = (slic.0)
                                .as_bytes()
                                .get(span.first as usize + 1)
                                .map(|&u| u as char)
                                .filter(|c| c.is_ascii())
                                .ok_or(FromJsonError::LiteralFormat(
                                    "expect char with 1 character".to_owned(),
                                    span.first,
                                    span.end,
                                ))?;
                        }
                        _ => unreachable!("unhandled hardcoded field while from json"),
                    }
                    Some(val)
                } else {
                    // Decode heuristic
                    let tok = lex.next().ok_or(FromJsonError::UnexpEOF)?;
                    let span = span!(&tok.buf);
                    Some(match tok.kind {
                        TokenType::Number => {
                            FieldType::Int(slic[span].parse::<i32>().map_err(|_| {
                                FromJsonError::LiteralFormat(
                                    "expected int".to_owned(),
                                    span.first,
                                    span.end,
                                )
                            })?)
                        }
                        TokenType::String => {
                            let string = &slic[span];
                            FieldType::String(string[1..string.len() - 1].to_owned())
                        }
                        TokenType::BracketOpen => {
                            let tok = lex.next().ok_or(FromJsonError::UnexpEOF)?;
                            let span = span!(tok.buf);
                            let b1 = match tok.kind {
                                TokenType::BooleanTrue => true,
                                TokenType::BooleanFalse => false,
                                _ => {
                                    return Err(FromJsonError::LiteralFormat(
                                        "expected bool".to_owned(),
                                        span.first,
                                        span.end,
                                    ))
                                }
                            };
                            Self::expect(lex, TokenType::Comma, true)?;
                            let tok = lex.next().ok_or(FromJsonError::UnexpEOF)?;
                            let span = span!(tok.buf);
                            let b2 = match tok.kind {
                                TokenType::BooleanTrue => true,
                                TokenType::BooleanFalse => false,
                                _ => {
                                    return Err(FromJsonError::LiteralFormat(
                                        "expected bool".to_owned(),
                                        span.first,
                                        span.end,
                                    ))
                                }
                            };
                            Self::expect(lex, TokenType::BracketClose, true)?;
                            FieldType::TwoBool(b1, b2)
                        }
                        TokenType::BooleanTrue => FieldType::Bool(true),
                        TokenType::BooleanFalse => FieldType::Bool(false),
                        _ => {
                            return Err(FromJsonError::LiteralFormat(
                                "unknown field".to_owned(),
                                span.first,
                                span.end,
                            ))
                        }
                    })
                }
            }
        };
        if let Some(val) = val {
            self.dat.push(Field {
                name: name.to_owned(),
                parent,
                tipe: val,
            });
        }
        name_stack.pop();
        Ok(field_index)
    }

    fn expect<T: Iterator<Item = Token>>(
        lex: &mut std::iter::Peekable<T>,
        exp: TokenType,
        eat: bool,
    ) -> Result<Token, FromJsonError> {
        let tok = if eat {
            lex.next().ok_or(FromJsonError::JsonErr)?
        } else {
            lex.peek().ok_or(FromJsonError::JsonErr)?.clone()
        };
        if tok.kind != exp {
            let sp = span!(tok.buf);
            return Err(FromJsonError::Expected(
                format!("{:?}", exp),
                sp.first,
                sp.end,
            ));
        }
        Ok(tok)
    }

    pub fn write_to_json<W: Write>(
        &self,
        writer: &'_ mut W,
        indent: u32,
        allow_dupes: bool,
    ) -> std::io::Result<()> {
        writer.write_all(b"{\n")?;
        if let Some(root) = self.o.objs.iter().find(|o| o.parent.is_none()) {
            self.write_field(root.field, writer, indent + 1, false, allow_dupes)?;
        }
        indent!(writer, indent);
        writer.write_all(b"}")?;
        Ok(())
    }

    fn write_object<W: Write>(
        &self,
        field_idx: FieldIdx,
        writer: &'_ mut W,
        indent: u32,
        comma: bool,
        allow_dupes: bool,
    ) -> std::io::Result<()> {
        let dat = &self.dat[field_idx.0];
        if let FieldType::Object(ref c) = dat.tipe {
            if c.len() == 0 {
                if comma {
                    writer.write_all(b"{},\n")?;
                } else {
                    writer.write_all(b"{}\n")?;
                }
            } else {
                writer.write_all(b"{\n")?;
                let mut emitted_fields = if allow_dupes {
                    None
                } else {
                    Some(std::collections::HashSet::new())
                };
                for (idx, &child) in c.iter().enumerate() {
                    if let Some(ref mut emitted_fields) = emitted_fields {
                        if !emitted_fields.insert(&self.dat[child.0].name) {
                            continue;
                        }
                    }
                    self.write_field(child, writer, indent + 1, idx != c.len() - 1, allow_dupes)?;
                }
                indent!(writer, indent);
                if comma {
                    writer.write_all(b"},\n")?;
                } else {
                    writer.write_all(b"}\n")?;
                }
            }
        } else {
            unreachable!()
        }
        Ok(())
    }

    fn write_field<W: Write>(
        &self,
        field_idx: FieldIdx,
        writer: &'_ mut W,
        indent: u32,
        comma: bool,
        allow_dupes: bool,
    ) -> std::io::Result<()> {
        use FieldType::*;
        let dat = &self.dat[field_idx.0];
        indent!(writer, indent);
        writer.write_all(b"\"")?;
        writer.write_all(dat.name.as_bytes())?;
        writer.write_all(b"\" : ")?;
        match &dat.tipe {
            Bool(b) => writer.write_fmt(format_args!("{}", b))?,
            TwoBool(b1, b2) => {
                writer.write_fmt(format_args!("[{}, {}]", b1, b2))?;
            }
            Int(i) => writer.write_fmt(format_args!("{}", i))?,
            Float(f) => writer.write_fmt(format_args!("{}", f))?,
            Char(c) => writer.write_fmt(format_args!("\"{}\"", c))?,
            String(s) => writer.write_fmt(format_args!("\"{}\"", s.replace("\n", "\\n")))?,
            IntVector(ref v) => {
                writer.write_all(b"[")?;
                for (idx, num) in v.iter().enumerate() {
                    writer.write_all(num.to_string().as_bytes())?;
                    if idx != v.len() - 1 {
                        writer.write_all(b", ")?;
                    }
                }
                writer.write_all(b"]")?;
            }
            StringVector(ref v) => {
                writer.write_all(b"[")?;
                for (idx, s) in v.iter().enumerate() {
                    writer.write_fmt(format_args!("\"{}\"", s.replace("\n", "\\n")))?;
                    if idx != v.len() - 1 {
                        writer.write_all(b", ")?;
                    }
                }
                writer.write_all(b"]")?;
            }
            FloatArray(ref v) => {
                writer.write_all(b"[")?;
                for (idx, f) in v.iter().enumerate() {
                    writer.write_all(f.to_string().as_bytes())?;
                    if idx != v.len() - 1 {
                        writer.write_all(b", ")?;
                    }
                }
                writer.write_all(b"]")?;
            }
            TwoInt(i1, i2) => {
                writer.write_fmt(format_args!("[{}, {}]", i1, i2))?;
            }
            File(ref obf) => {
                let fil = obf.as_ref().unwrap();
                fil.write_to_json(writer, indent, allow_dupes)?;
            }
            Object(_) => {
                self.write_object(field_idx, writer, indent, comma, allow_dupes)?;
            }
        };

        if let Object(_) = dat.tipe {
        } else if comma {
            writer.write_all(b",\n")?;
        } else {
            writer.write_all(b"\n")?;
        }

        Ok(())
    }

    fn calc_bin_size(&self) -> usize {
        let meta_size = self.h.calc_bin_size() + self.o.calc_bin_size() + self.f.calc_bin_size();
        let mut existing_size = 0;
        for f in &self.dat {
            existing_size = f.add_bin_size(existing_size);
        }
        meta_size + existing_size
    }

    fn fixup_offsets(&mut self) {
        let mut offset = 0;
        for (idx, f) in (&mut self.f.fields).into_iter().enumerate() {
            f.offset = offset;
            offset = self.dat[idx].add_bin_size(offset as usize) as u32;
        }
    }
}

const HEADER_MAGIC_NUMBER: &[u8] = &[0x01, 0xB1, 0x00, 0x00];
#[derive(Debug, Clone, Default)]
struct Header {
    magic: [u8; 4],
    version: [u8; 4],
    header_len: u32,
    //zeroes1: [u8; 4],
    objects_size: u32,
    objects_num: u32,
    objects_offset: u32,
    //zeroes2: [u8; 16],
    fields_num: u32,
    fields_offset: u32,
    //zeroes3: [u32; 4],
    data_size: u32,
    data_offset: u32,
}

impl Header {
    pub fn try_from_reader<R: Read>(reader: &'_ mut R) -> Result<Self, FromBinError> {
        // Reading all 64 header bytes upfront elides all the checks in later unwraps/?s.
        let buf = &mut [0u8; 64];
        reader.read_exact(buf)?;
        let mut reader: &[u8] = buf;

        let tmp = &mut [0u8; 4];
        let magic = {
            reader.read_exact(tmp).unwrap();
            *tmp
        };
        let version = {
            reader.read_exact(tmp).unwrap();
            *tmp
        };
        let header_len = reader.read_u32::<LittleEndian>().unwrap();
        let _ = reader.read_u32::<LittleEndian>().unwrap(); // Zeroes
        let objects_size = reader.read_u32::<LittleEndian>().unwrap();
        let objects_num = reader.read_u32::<LittleEndian>().unwrap();
        let objects_offset = reader.read_u32::<LittleEndian>().unwrap();
        let _ = reader.read_u64::<LittleEndian>().unwrap();
        let _ = reader.read_u64::<LittleEndian>().unwrap();
        let fields_num = reader.read_u32::<LittleEndian>().unwrap();
        let fields_offset = reader.read_u32::<LittleEndian>().unwrap();
        let _ = reader.read_u32::<LittleEndian>().unwrap();
        let data_size = reader.read_u32::<LittleEndian>().unwrap();
        let data_offset = reader.read_u32::<LittleEndian>().unwrap();
        Ok(Header {
            magic,
            version,
            header_len,
            //zeroes1: Default::default(),
            objects_size,
            objects_num,
            objects_offset,
            //zeroes2: Default::default(),
            fields_num,
            fields_offset,
            //zeroes3: Default::default(),
            data_size,
            data_offset,
        })
    }

    fn calc_bin_size(&self) -> usize {
        64
    }
}
#[derive(Clone, Debug)]
struct ObjectInfo {
    parent: Option<ObjIdx>,
    field: FieldIdx,
    num_direct_childs: u32,
    num_all_childs: u32,
}
#[derive(Clone, Default, Debug)]
struct Objects {
    objs: Vec<ObjectInfo>,
}

impl Objects {
    fn try_from_reader<R: Read>(reader: &'_ mut R, num: u32) -> Result<Self, FromBinError> {
        let mut o = Objects {
            objs: Vec::with_capacity(num as usize),
        };
        let mut buf = vec![0u8; 4 * 4];
        for _ in 0..num {
            reader.read_exact(&mut buf)?;
            let mut reader: &[u8] = &buf;
            let parent = reader.read_i32::<LittleEndian>().unwrap();
            let field = reader.read_u32::<LittleEndian>().unwrap();
            let num_direct_childs = reader.read_u32::<LittleEndian>().unwrap();
            let num_all_childs = reader.read_u32::<LittleEndian>().unwrap();
            o.objs.push(ObjectInfo {
                parent: usize::try_from(parent).ok().map(|i| ObjIdx(i)),
                field: FieldIdx(field as usize),
                num_direct_childs,
                num_all_childs,
            })
        }
        Ok(o)
    }

    fn calc_bin_size(&self) -> usize {
        4 * 4 * self.objs.len()
    }
}

#[derive(Clone, Default, Debug)]
struct Fields {
    fields: Vec<FieldInfo>,
}
#[derive(Clone, Default, Debug)]
struct FieldInfo {
    name_hash: i32,
    offset: u32,
    field_info: u32,
}

#[derive(Copy, Clone, Debug)]
pub struct ObjIdx(usize);
#[derive(Copy, Clone, Debug)]
pub struct FieldIdx(usize);

impl Fields {
    fn try_from_reader<R: Read + Seek>(reader: &'_ mut R, num: u32) -> Result<Self, FromBinError> {
        let mut f = Fields { fields: vec![] };
        let mut buf = vec![0u8; 3 * 4];
        for _ in 0..num {
            reader.read_exact(&mut buf)?;
            let mut reader: &[u8] = &buf;
            let name_hash = reader.read_i32::<LittleEndian>().unwrap();
            let offset = reader.read_u32::<LittleEndian>().unwrap();
            let field_info = reader.read_u32::<LittleEndian>().unwrap();
            f.fields.push(FieldInfo {
                name_hash,
                offset,
                field_info,
            })
        }
        Ok(f)
    }

    fn calc_bin_size(&self) -> usize {
        4 * 3 * self.fields.len()
    }
}

impl FieldInfo {
    fn is_object(&self) -> bool {
        (self.field_info & 0b1) == 1
    }

    fn name_length(&self) -> u32 {
        (self.field_info >> 2) & 0b111111111
    }

    fn object_index(&self) -> Option<ObjIdx> {
        if self.is_object() {
            Some(ObjIdx(
                ((self.field_info >> 11) & 0b11111111111111111111) as usize,
            ))
        } else {
            None
        }
    }
}

#[derive(Clone, Debug)]
pub struct Field {
    name: String,
    parent: Option<ObjIdx>,
    tipe: FieldType,
}

impl Field {
    fn add_bin_size(&self, mut existing_size: usize) -> usize {
        use FieldType::*;

        existing_size += self.name.len() + 1;
        let align = ((existing_size + 3) & !0b11) - existing_size;
        existing_size += match &self.tipe {
            Bool(_) => 1,
            TwoBool(_, _) => align + 8,
            Int(_) => align + 4,
            Float(_) => align + 4,
            Char(_) => 1,
            String(ref s) => align + 4 + s.len() + 1,
            IntVector(ref v) => align + 4 + v.len(),
            StringVector(ref v) => {
                let mut tmp_size = align;
                tmp_size += 4;
                for s in v {
                    tmp_size = (tmp_size + 3) & 0b11;
                    tmp_size += 4;
                    tmp_size += s.len() + 1;
                }
                tmp_size
            }
            FloatArray(ref v) => align + 4 * v.len(),
            TwoInt(_, _) => align + 8,
            File(Some(ref f)) => align + 4 + f.calc_bin_size(),
            File(None) => unreachable!("file already en-/decoded"),
            Object(_) => 0,
        };

        existing_size
    }
}

#[derive(Clone, Debug)]
pub enum FieldType {
    Bool(bool),
    TwoBool(bool, bool),
    Int(i32),
    Float(f32),
    Char(char),
    String(String),
    IntVector(Vec<i32>),
    StringVector(Vec<String>),
    FloatArray(Vec<f32>),
    TwoInt(i32, i32),
    File(Option<Box<File>>),
    Object(Vec<FieldIdx>),
}
#[derive(Debug)]
pub enum FromBinError {
    UnknownField(String),
    SizeMismatch { at: usize, exp: usize },
    OffsetMismatch { exp: u64, is: u64 },
    IoError(std::io::Error),
    HashMismatch,
    Utf8Error(std::str::Utf8Error),
    CharError(u8),
    FromBytesWithNulError(std::ffi::FromBytesWithNulError),
    TryReserveError(std::collections::TryReserveError),
    MissingRoot,
    Arith,
    FormatErr,
}

impl From<std::str::Utf8Error> for FromBinError {
    fn from(err: std::str::Utf8Error) -> Self {
        FromBinError::Utf8Error(err)
    }
}

impl From<std::ffi::FromBytesWithNulError> for FromBinError {
    fn from(err: std::ffi::FromBytesWithNulError) -> Self {
        FromBinError::FromBytesWithNulError(err)
    }
}

impl From<std::io::Error> for FromBinError {
    fn from(err: std::io::Error) -> Self {
        FromBinError::IoError(err)
    }
}

impl From<std::collections::TryReserveError> for FromBinError {
    fn from(err: std::collections::TryReserveError) -> Self {
        FromBinError::TryReserveError(err)
    }
}

impl std::fmt::Display for FromBinError {
    fn fmt(&self, fmt: &mut std::fmt::Formatter<'_>) -> std::result::Result<(), std::fmt::Error> {
        std::fmt::Debug::fmt(self, fmt)
    }
}

impl std::error::Error for FromBinError {}

#[derive(Debug)]
pub enum FromJsonError {
    UnknownField(String),
    Expected(String, u64, u64),
    LiteralFormat(String, u64, u64),
    MissingRoot,
    UnexpEOF,
    JsonErr,
}

impl std::fmt::Display for FromJsonError {
    fn fmt(&self, fmt: &mut std::fmt::Formatter<'_>) -> std::result::Result<(), std::fmt::Error> {
        std::fmt::Debug::fmt(self, fmt)
    }
}

impl std::error::Error for FromJsonError {}

macro_rules! types {
    ($([$e:ident, $($i:literal),+]), + $(,)*) => {
        &[$((types!($e), &[$($i,)+]),)+]
    };
    (Float) => {FieldType::Float(0.0)};
    (Char) => {FieldType::Char('\0')};
    (IntVector) => {FieldType::IntVector(std::vec::Vec::new())};
    (StringVector) => {FieldType::StringVector(std::vec::Vec::new())};
    (FloatArray) => {FieldType::FloatArray(std::vec::Vec::new())};
    (TwoInt) => {FieldType::TwoInt(0, 0)};
}

#[rustfmt::skip]
const TYPES: &[(FieldType, &[&'static str])] = types!(
    [Char, "requirement_code"],

    [Float, "current_hp"],
    [Float, "m_Stress"],
    [Float, "actor", "buff_group", "*", "amount"],
    [Float, "chapters", "*", "*", "percent"],
    [Float, "non_rolled_additional_chances", "*", "chance"],
    [Float, "rarity_table", "*", "chance"],
    [Float, "chance_of_loot"],
    [Float, "shard_consume_percent"],
    [Float, "chances", "*"],
    [Float, "chance_sum"],

    [IntVector, "read_page_indexes"],
    [IntVector, "raid_read_page_indexes"],
    [IntVector, "raid_unread_page_indexes"],
    [IntVector, "dungeons_unlocked"],
    [IntVector, "played_video_list"],
    [IntVector, "trinket_retention_ids"],
    [IntVector, "last_party_guids"],
    [IntVector, "dungeon_history"],
    [IntVector, "buff_group_guids"],
    [IntVector, "result_event_history"],
    [IntVector, "additional_mash_disabled_infestation_monster_class_ids"],
    [IntVector, "party", "heroes"],
    [IntVector, "skill_cooldown_keys"],
    [IntVector, "skill_cooldown_values"],
    [IntVector, "bufferedSpawningSlotsAvailable"],
    [IntVector, "curioGroups", "*", "curios"],
    [IntVector, "curioGroups", "*", "curio_table_entries"],
    [IntVector, "narration_audio_event_queue_tags"],
    [IntVector, "dispatched_events"],

    [StringVector, "goal_ids"],
    [StringVector, "roaming_dungeon_2_ids", "*", "s"],
    [StringVector, "quirk_group"],
    [StringVector, "backgroundNames"],
    [StringVector, "backgroundGroups", "*", "backgrounds"],
    [StringVector, "backgroundGroups", "*", "background_table_entries"],

    [FloatArray, "map", "bounds"],
    [FloatArray, "areas", "*", "bounds"],
    [FloatArray, "areas", "*", "tiles", "*", "mappos"],
    [FloatArray, "areas", "*", "tiles", "*", "sidepos"],

    [TwoInt, "killRange"],
);

fn hardcoded_type<T: AsRef<str>>(name_trace: &'_ [T]) -> Option<FieldType> {
    TYPES.iter().find_map(|(t, path)| {
        if name_trace.len() >= path.len()
            && path
                .iter()
                .rev()
                .zip(name_trace.iter().rev())
                .all(|(tst, name_frag)| tst == &"*" || tst == &name_frag.as_ref())
        {
            Some(t).cloned()
        } else {
            None
        }
    })
}

macro_rules! skip {
    ($r:expr, $num:expr) => {
        std::io::copy(&mut $r.by_ref().take($num as u64), &mut std::io::sink())?;
    };
}

fn decode_fields<R: Read + Seek>(
    reader: &'_ mut R,
    f: &'_ mut Fields,
    o: &'_ Objects,
    max_size: u32,
) -> Result<Vec<Field>, FromBinError> {
    let mut buf = vec![0; max_size as usize];
    reader.read_exact(&mut buf)?;

    //let mut offset_sizes = std::collections::HashMap::<u32, usize>::new();
    let mut offset_sizes = std::collections::BTreeMap::<u32, usize>::new();
    let mut offsets = f.fields.iter().map(|f| f.offset).collect::<Vec<_>>();
    offsets.sort_unstable();
    for offs in offsets.windows(2) {
        offset_sizes.insert(offs[0], (offs[1] - offs[0]) as usize);
    }
    if let Some(&last) = offsets.last() {
        offset_sizes.insert(
            last,
            max_size.checked_sub(last).ok_or(FromBinError::Arith)? as usize,
        );
    }
    //println!("{:?}", offset_sizes);
    let mut data = Vec::new();
    let mut obj_stack = Vec::new();
    let mut obj_nums = Vec::new();
    for (idx, field) in f.fields.iter().enumerate() {
        // Read name
        let off = field.offset as usize;
        let len = field.name_length() as usize;
        let field_name = buf
            .get(off..off + len)
            .ok_or(FromBinError::SizeMismatch { at: off, exp: len })?;
        let name = CStr::from_bytes_with_nul(&field_name)?
            .to_str()?
            .to_string();

        if name_hash(&name) != field.name_hash {
            return Err(FromBinError::HashMismatch);
        }

        let data_begin = off + len;
        let data_end = off + offset_sizes[&field.offset]; // exclusive
        let name_trace = build_name_trace(&data, &o.objs, &name, &obj_stack)?;
        let field_type = if field.is_object() {
            FieldType::Object(vec![])
        } else {
            if data_end <= data_begin {
                return Err(FromBinError::FormatErr);
            }
            let to_skip_if_aligned = ((data_begin + 3) & !0b11) - data_begin;
            let mut field_data =
                buf.get(data_begin..data_end)
                    .ok_or(FromBinError::SizeMismatch {
                        at: data_begin,
                        exp: data_end
                            .checked_sub(data_begin)
                            .ok_or(FromBinError::FormatErr)?,
                    })?;
            FieldType::try_from_reader(
                &mut field_data,
                to_skip_if_aligned,
                data_end - data_begin,
                &name_trace,
            )?
        };
        //println!("{}", name);
        data.push(self::Field {
            name,
            parent: obj_stack.last().copied(),
            tipe: field_type,
        });

        if obj_stack.is_empty() {
            if !field.is_object() {
                return Err(FromBinError::MissingRoot);
            }
        } else {
            if let FieldType::Object(ref mut v) = data[o
                .objs
                .get(obj_stack.last().unwrap().0)
                .ok_or(FromBinError::FormatErr)?
                .field
                .0]
                .tipe
            {
                v.push(FieldIdx(idx))
            } else {
                return Err(FromBinError::FormatErr);
            }
            *obj_nums.last_mut().unwrap() += 1;
        }

        if field.is_object() {
            obj_stack.push(field.object_index().unwrap());
            obj_nums.push(0u32);
        }

        while !obj_stack.is_empty()
            && *obj_nums.last().unwrap()
                == o.objs
                    .get(obj_stack.last().unwrap().0)
                    .ok_or(FromBinError::FormatErr)?
                    .num_direct_childs
        {
            obj_stack.pop();
            obj_nums.pop();
        }
    }

    Ok(data)
}

fn build_name_trace<'a>(
    fields: &'a [Field],
    objs: &'a [ObjectInfo],
    name: &'a str,
    obj_stack: &'a [ObjIdx],
) -> Result<Vec<&'a str>, FromBinError> {
    obj_stack
        .iter()
        .map(|i| {
            Ok(fields
                .get(objs[i.0].field.0)
                .ok_or(FromBinError::FormatErr)?
                .name
                .as_str())
        })
        .chain(std::iter::once(Ok(name)))
        .collect::<Result<Vec<_>, _>>()
}

impl FieldType {
    pub fn try_from_reader<R: Read>(
        reader: &'_ mut R,
        to_skip_if_aligned: usize,
        max_len: usize,
        name_trace: &'_ [&'_ str],
    ) -> Result<Self, FromBinError> {
        use FieldType::*;

        if let Some(mut val) = hardcoded_type(name_trace) {
            match &mut val {
                Float(ref mut f) => {
                    skip!(reader, to_skip_if_aligned);
                    *f = reader.read_f32::<LittleEndian>()?;
                }
                IntVector(ref mut v) => {
                    skip!(reader, to_skip_if_aligned);
                    let num = reader.read_u32::<LittleEndian>()? as usize;
                    v.try_reserve(num)?;
                    for _ in 0..num {
                        v.push(reader.read_i32::<LittleEndian>()?);
                    }
                }
                StringVector(ref mut v) => {
                    skip!(reader, to_skip_if_aligned);
                    let num = reader.read_u32::<LittleEndian>()? as usize;
                    v.try_reserve(num)?;
                    let mut to_skip = 0;
                    for _ in 0..num {
                        skip!(reader, to_skip);
                        let len = reader.read_u32::<LittleEndian>()? as usize;
                        let mut buf = vec![0u8; len];
                        reader.read_exact(&mut buf)?;
                        let str: &str = CStr::from_bytes_with_nul(&buf)?.to_str()?;
                        v.push(str.to_string());
                        to_skip = ((len + 3) & !0b11) - len;
                    }
                }
                FloatArray(ref mut v) => {
                    skip!(reader, to_skip_if_aligned);
                    let num = (max_len - to_skip_if_aligned) / 4;
                    for _ in 0..num {
                        v.push(reader.read_f32::<LittleEndian>()?);
                    }
                }
                TwoInt(ref mut i1, ref mut i2) => {
                    skip!(reader, to_skip_if_aligned);
                    *i1 = reader.read_i32::<LittleEndian>()?;
                    *i2 = reader.read_i32::<LittleEndian>()?;
                }
                Char(ref mut c) => {
                    let b = &mut [0u8];
                    reader.read_exact(b)?;
                    let a = char::from(b[0]);
                    if a.is_ascii() {
                        *c = a;
                    } else {
                        return Err(FromBinError::CharError(b[0]));
                    }
                }
                _ => unreachable!("unhandled hardcoded type when reading"),
            }
            Ok(val)
        } else {
            if max_len == 1 {
                let r = &mut [0u8];
                reader.read_exact(r)?;
                Ok(Bool(r[0] != 0))
            } else {
                let aligned_max_len = max_len - to_skip_if_aligned;
                skip!(reader, to_skip_if_aligned);
                if aligned_max_len == 4 {
                    Ok(Int(reader.read_i32::<LittleEndian>()?))
                } else {
                    let first_int = reader.read_i32::<LittleEndian>()?;
                    if aligned_max_len == 8 && (first_int == 0 || first_int == 1) {
                        Ok(TwoBool(
                            first_int != 0,
                            reader.read_i32::<LittleEndian>()? != 0,
                        ))
                    } else if (first_int as usize)
                        .checked_add(4)
                        .ok_or(FromBinError::FormatErr)?
                        == aligned_max_len
                    {
                        let len = first_int;
                        let mut buf = vec![0u8; len as usize];
                        reader.read_exact(&mut buf)?;
                        if len >= 4 && &buf[0..4] == HEADER_MAGIC_NUMBER {
                            Ok(File(Some(Box::new(self::File::try_from_reader(
                                &mut Cursor::new(buf),
                            )?))))
                        } else {
                            let str: &str = CStr::from_bytes_with_nul(&buf)?.to_str()?;
                            Ok(String(str.to_string()))
                        }
                    } else {
                        Err(FromBinError::UnknownField(
                            name_trace.last().unwrap().to_string(),
                        ))
                    }
                }
            }
        }
    }
}
