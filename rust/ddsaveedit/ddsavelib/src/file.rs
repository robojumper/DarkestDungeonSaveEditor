use byteorder::{LittleEndian, ReadBytesExt};
use std::{
    convert::TryFrom,
    ffi::CStr,
    io::{Cursor, Read, Seek, Write},
};

use super::{
    json::{JsonError, Parser, Token, TokenType},
    util::{escape, name_hash},
};

#[derive(Clone, Debug, PartialEq)]
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

impl File {
    const BUILTIN_VERSION_FIELD: &'static str = "__revision_dont_touch";

    pub fn try_from_bin<R: Read + Seek>(reader: &'_ mut R) -> Result<Self, FromBinError> {
        let h = Header::try_from_bin(reader)?;
        check_offset!(reader, h.objects_offset);
        let o: Objects = Objects::try_from_bin(reader, h.objects_num)?;

        check_offset!(reader, h.fields_offset);
        let mut f = Fields::try_from_bin(reader, h.fields_num)?;

        check_offset!(reader, h.data_offset);
        let dat = decode_fields(reader, &mut f, &o, h.data_size)?;
        Ok(File { h, o, f, dat })
    }

    /// Decode a `File` from a `Read` representing a JSON stream.
    #[inline(never)]
    pub fn try_from_json<R: Read>(reader: &'_ mut R) -> Result<Self, FromJsonError> {
        let mut x = vec![];
        reader.read_to_end(&mut x).unwrap();
        let slic = std::str::from_utf8(&x).unwrap();

        let lex = &mut Parser::new(slic).peekable();
        Self::try_from_json_parser(lex)
    }

    fn try_from_json_parser<'a, T: Iterator<Item = Result<Token<'a>, JsonError>>>(
        lex: &mut std::iter::Peekable<T>,
    ) -> Result<Self, FromJsonError> {
        let mut s = Self {
            h: Default::default(),
            o: Default::default(),
            f: Default::default(),
            dat: vec![],
        };

        Self::expect(lex, TokenType::BeginObject, true)?;

        let vers_field_tok = Self::expect(lex, TokenType::FieldName, true)?;
        if vers_field_tok.dat != Self::BUILTIN_VERSION_FIELD {
            return Err(FromJsonError::Expected(
                format!("{}", Self::BUILTIN_VERSION_FIELD),
                vers_field_tok.loc.first,
                vers_field_tok.loc.end,
            ));
        }
        let vers = Self::expect(lex, TokenType::Number, true)?;
        let vers_num: u32 = vers.dat.parse::<u32>().map_err(|_| {
            FromJsonError::LiteralFormat(
                "expected version num".to_owned(),
                vers.loc.first,
                vers.loc.end,
            )
        })?;

        let mut name_stack = vec![];

        s.read_child_fields(None, &mut name_stack, lex)?;
        s.fixup_offsets();
        s.fixup_header();
        s.h.version = vers_num;
        Ok(s)
    }

    fn read_child_fields<'a, T: Iterator<Item = Result<Token<'a>, JsonError>>>(
        &mut self,
        parent: Option<ObjIdx>,
        name_stack: &mut Vec<String>,
        lex: &mut std::iter::Peekable<T>,
    ) -> Result<Vec<FieldIdx>, FromJsonError> {
        let mut child_fields = vec![];

        loop {
            let tok = lex.next().ok_or(JsonError::EOF)??;
            match tok.kind {
                TokenType::EndObject => break,
                TokenType::FieldName => {
                    let name = tok.dat;
                    let idx = self.read_field(name.as_ref(), parent, name_stack, lex)?;
                    child_fields.push(idx);
                }
                _ => {
                    return Err(FromJsonError::Expected(
                        "name or }".to_owned(),
                        tok.loc.first,
                        tok.loc.end,
                    ))
                }
            }
        }

        Ok(child_fields)
    }

    fn read_field<'a, T: Iterator<Item = Result<Token<'a>, JsonError>>>(
        &mut self,
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
        let val = match lex.peek().ok_or(FromJsonError::UnexpEOF)?.clone()?.kind {
            TokenType::BeginObject => {
                if name == "raw_data" || name == "static_save" {
                    let inner = File::try_from_json_parser(lex)?;
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
                    let childs = self.read_child_fields(Some(obj_index), name_stack, lex)?;
                    self.o.objs[obj_index.0].num_direct_childs = childs.len() as u32;
                    self.o.objs[obj_index.0].num_all_childs =
                        (self.f.fields.len() - 1 - field_index.0) as u32;
                    if let FieldType::Object(ref mut chl) = self.dat[field_index.0].tipe {
                        *chl = childs;
                    } else {
                        unreachable!("pushed obj earlier");
                    }
                    None
                }
            }
            _ => {
                macro_rules! parse_prim {
                    ($tok:expr, $t:ty, $err:expr) => {{
                        let tok = $tok;
                        tok.dat.parse::<$t>().map_err(|_| {
                            FromJsonError::LiteralFormat(
                                $err.to_owned(),
                                tok.loc.first,
                                tok.loc.end,
                            )
                        })?
                    }};
                }

                if let Some(mut val) = hardcoded_type(&name_stack) {
                    match &mut val {
                        FieldType::Float(ref mut f) => {
                            let tok = Self::expect(lex, TokenType::Number, true)?;
                            *f = parse_prim!(tok, f32, "float");
                        }
                        FieldType::IntVector(ref mut v) => {
                            Self::expect(lex, TokenType::BeginArray, true)?;
                            loop {
                                let tok = lex.next().ok_or(FromJsonError::UnexpEOF)??;
                                match tok.kind {
                                    TokenType::EndArray => break,
                                    TokenType::Number => {
                                        v.push(parse_prim!(tok, i32, "integer"));
                                    }
                                    _ => {
                                        return Err(FromJsonError::Expected(
                                            "string or ]".to_owned(),
                                            tok.loc.first,
                                            tok.loc.end,
                                        ))
                                    }
                                }
                            }
                        }
                        FieldType::StringVector(ref mut v) => {
                            Self::expect(lex, TokenType::BeginArray, true)?;
                            loop {
                                let tok = lex.next().ok_or(FromJsonError::UnexpEOF)??;
                                match tok.kind {
                                    TokenType::EndArray => break,
                                    TokenType::String => {
                                        v.push(parse_prim!(tok, String, "string"));
                                    }
                                    _ => {
                                        return Err(FromJsonError::Expected(
                                            "string or ]".to_owned(),
                                            tok.loc.first,
                                            tok.loc.end,
                                        ))
                                    }
                                }
                            }
                        }
                        FieldType::FloatArray(ref mut v) => {
                            Self::expect(lex, TokenType::BeginArray, true)?;
                            loop {
                                let tok = lex.next().ok_or(FromJsonError::UnexpEOF)??;
                                match tok.kind {
                                    TokenType::EndArray => break,
                                    TokenType::Number => {
                                        v.push(parse_prim!(tok, f32, "float"));
                                    }
                                    _ => {
                                        return Err(FromJsonError::Expected(
                                            "string or ]".to_owned(),
                                            tok.loc.first,
                                            tok.loc.end,
                                        ))
                                    }
                                }
                            }
                        }
                        FieldType::TwoInt(ref mut i1, ref mut i2) => {
                            Self::expect(lex, TokenType::BeginArray, true)?;
                            let t1 = Self::expect(lex, TokenType::Number, true)?;
                            *i1 = parse_prim!(t1, i32, "integer");
                            let t2 = Self::expect(lex, TokenType::Number, true)?;
                            *i2 = parse_prim!(t2, i32, "integer");
                            Self::expect(lex, TokenType::EndArray, true)?;
                        }
                        FieldType::Char(ref mut c) => {
                            let tok = Self::expect(lex, TokenType::String, true)?;
                            if tok.dat.len() == 1 {
                                let tmp = tok.dat.chars().next().unwrap();
                                if tmp.is_ascii() {
                                    *c = tmp;
                                } else {
                                    return Err(FromJsonError::LiteralFormat(
                                        "ascii char".to_owned(),
                                        tok.loc.first,
                                        tok.loc.end,
                                    ));
                                }
                            } else {
                                return Err(FromJsonError::LiteralFormat(
                                    "ascii char".to_owned(),
                                    tok.loc.first,
                                    tok.loc.end,
                                ));
                            }
                        }
                        _ => unreachable!("unhandled hardcoded field while from json"),
                    }
                    Some(val)
                } else {
                    // Decode heuristic
                    let tok = lex.next().ok_or(FromJsonError::UnexpEOF)??;
                    Some(match tok.kind {
                        TokenType::Number => FieldType::Int(parse_prim!(tok, i32, "integer")),
                        TokenType::String => FieldType::String(parse_prim!(tok, String, "string")),
                        TokenType::BeginArray => {
                            let tok1 = lex.next().ok_or(FromJsonError::UnexpEOF)??;
                            let tok2 = lex.next().ok_or(FromJsonError::UnexpEOF)??;
                            Self::expect(lex, TokenType::EndArray, true)?;
                            let b1 = match tok1.kind {
                                TokenType::BoolTrue => true,
                                TokenType::BoolFalse => false,
                                _ => {
                                    return Err(FromJsonError::Expected(
                                        "boolean".to_owned(),
                                        tok1.loc.first,
                                        tok1.loc.end,
                                    ))
                                }
                            };
                            let b2 = match tok2.kind {
                                TokenType::BoolTrue => true,
                                TokenType::BoolFalse => false,
                                _ => {
                                    return Err(FromJsonError::Expected(
                                        "boolean".to_owned(),
                                        tok2.loc.first,
                                        tok2.loc.end,
                                    ))
                                }
                            };
                            FieldType::TwoBool(b1, b2)
                        }
                        TokenType::BoolTrue => FieldType::Bool(true),
                        TokenType::BoolFalse => FieldType::Bool(false),
                        _ => {
                            return Err(FromJsonError::LiteralFormat(
                                "unknown field".to_owned(),
                                tok.loc.first,
                                tok.loc.end,
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

    fn expect<'a, T: Iterator<Item = Result<Token<'a>, JsonError>>>(
        lex: &mut std::iter::Peekable<T>,
        exp: TokenType,
        eat: bool,
    ) -> Result<Token<'a>, FromJsonError> {
        let tok = if eat {
            lex.next().ok_or(FromJsonError::UnexpEOF)??
        } else {
            lex.peek().ok_or(FromJsonError::UnexpEOF)?.clone()?
        };
        if tok.kind != exp {
            return Err(FromJsonError::Expected(
                format!("file.rs: {:?}", exp),
                tok.loc.first,
                tok.loc.end,
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
        indent!(writer, indent + 1);
        writer.write_fmt(format_args!(
            "\"{}\": {},\n",
            Self::BUILTIN_VERSION_FIELD,
            self.h.version
        ))?;
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
        /*indent!(writer, indent);
        writer.write_fmt(format_args!(
            "// Unknown bits: {}, {}\n",
            self.f.fields[field_idx.0].unknown_bit(),
            self.f.fields[field_idx.0].unused_bit2()
        ))?;*/
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
            String(s) => writer.write_fmt(format_args!("\"{}\"", escape(s)))?,
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
                    writer.write_fmt(format_args!("\"{}\"", escape(s)))?;
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
        self.h.data_size = offset;
    }

    fn fixup_header(&mut self) {
        self.h.magic = HEADER_MAGIC_NUMBER;
        self.h.header_len = Header::SIZE as u32;
        self.h.objects_num = self.o.objs.len() as u32;
        self.h.objects_size = self.h.objects_num * 16;
        self.h.objects_offset = self.h.header_len;
        self.h.fields_num = self.f.fields.len() as u32;
        self.h.fields_offset = self.h.objects_offset + self.h.objects_size;
        self.h.data_offset = self.h.fields_offset + self.h.fields_num * 12;
    }
}

const HEADER_MAGIC_NUMBER: [u8; 4] = [0x01, 0xB1, 0x00, 0x00];
#[derive(Debug, Clone, Default, PartialEq, Eq)]
struct Header {
    magic: [u8; 4],
    //version: [u8; 4],
    version: u32,
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
    const SIZE: usize = 64;

    pub fn try_from_bin<R: Read>(reader: &'_ mut R) -> Result<Self, FromBinError> {
        // Reading all 64 header bytes upfront elides all the checks in later unwraps/?s.
        let buf = &mut [0u8; Header::SIZE];
        reader.read_exact(buf)?;
        let mut reader: &[u8] = buf;

        let tmp = &mut [0u8; 4];
        let magic = {
            reader.read_exact(tmp).unwrap();
            *tmp
        };
        /*let version = {
            reader.read_exact(tmp).unwrap();
            *tmp
        };*/
        let version = reader.read_u32::<LittleEndian>().unwrap();
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
        Header::SIZE
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
struct ObjectInfo {
    parent: Option<ObjIdx>,
    field: FieldIdx,
    num_direct_childs: u32,
    num_all_childs: u32,
}
#[derive(Clone, Default, Debug, Eq, PartialEq)]
struct Objects {
    objs: Vec<ObjectInfo>,
}

impl Objects {
    fn try_from_bin<R: Read>(reader: &'_ mut R, num: u32) -> Result<Self, FromBinError> {
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

#[derive(Clone, Default, Debug, PartialEq, Eq)]
struct Fields {
    fields: Vec<FieldInfo>,
}
#[derive(Clone, Default, Debug, PartialEq, Eq)]
struct FieldInfo {
    name_hash: i32,
    offset: u32,
    field_info: u32,
}

#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub struct ObjIdx(usize);
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub struct FieldIdx(usize);

impl Fields {
    fn try_from_bin<R: Read + Seek>(reader: &'_ mut R, num: u32) -> Result<Self, FromBinError> {
        let mut f = Fields { fields: vec![] };
        let mut buf = vec![0u8; 3 * 4];
        for _ in 0..num {
            reader.read_exact(&mut buf)?;
            let mut reader: &[u8] = &buf;
            let name_hash = reader.read_i32::<LittleEndian>().unwrap();
            let offset = reader.read_u32::<LittleEndian>().unwrap();
            // Garbage upper bit?
            let field_info = reader.read_u32::<LittleEndian>().unwrap() & !0x8000_0000;
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

    /*
    fn unknown_bit(&self) -> bool {
        (self.field_info & 0x8000_0000) != 0
    }

    fn unused_bit2(&self) -> bool {
        (self.field_info & 0b10) != 0
    }
    */
}

#[derive(Clone, Debug, PartialEq)]
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
            IntVector(ref v) => align + 4 + v.len() * 4,
            StringVector(ref v) => {
                let mut tmp_size = 4;
                for s in v {
                    tmp_size = (tmp_size + 3) & !0b11;
                    tmp_size += 4;
                    tmp_size += s.len() + 1;
                }
                tmp_size + align
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

#[derive(Clone, Debug, PartialEq)]
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
    Expected(String, u64, u64),
    LiteralFormat(String, u64, u64),
    JsonErr(u64, u64),
    UnexpEOF,
}

impl std::fmt::Display for FromJsonError {
    fn fmt(&self, fmt: &mut std::fmt::Formatter<'_>) -> std::result::Result<(), std::fmt::Error> {
        std::fmt::Debug::fmt(self, fmt)
    }
}

impl From<JsonError> for FromJsonError {
    fn from(err: JsonError) -> Self {
        match err {
            JsonError::EOF => FromJsonError::UnexpEOF,
            JsonError::ExpectedValue(b, c) => FromJsonError::JsonErr(b, c),
            JsonError::BareControl(b, c) => {
                FromJsonError::LiteralFormat("bare control character".to_owned(), b, c)
            }
            JsonError::Expected(a, b, c) => FromJsonError::Expected(a, b, c),
        }
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

fn hardcoded_type<T: AsRef<str> + std::fmt::Debug>(name_trace: &'_ [T]) -> Option<FieldType> {
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
            FieldType::try_from_bin(
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
    pub fn try_from_bin<R: Read>(
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
                        let string = CStr::from_bytes_with_nul(&buf)?.to_str()?.to_string();
                        v.push(string);
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
                            Ok(File(Some(Box::new(self::File::try_from_bin(
                                &mut Cursor::new(buf),
                            )?))))
                        } else {
                            let string = CStr::from_bytes_with_nul(&buf)?.to_str()?.to_string();
                            Ok(String(string))
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
