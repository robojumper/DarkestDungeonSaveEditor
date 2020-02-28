use std::{
    convert::TryFrom,
    io::{Read, Seek, Write},
};

use crate::{err::*, util::escape};

mod json;
mod parts;
use json::{ExpectExt, Parser, Token, TokenType};
use parts::{Data, FieldIdx, FieldInfo, FieldType, Fields, Header, ObjIdx, Objects};

#[derive(Clone, Debug, PartialEq)]
/// Represents a valid Darkest Dungeon save file.
///
/// # A note on binary file identity
///
/// Files produced by the game currently contain unidentified bits.
/// Right now, if you read a binary file and encode it to binary
/// again, you will encounter some different bits. Theoretically,
/// sizes could be different due to data alignment, though this
/// has not been observed in practice yet.
/// Phrased differently, the Binary => [`File`] conversion is minimally lossy.
/// [`File`] => Binary and [`File`] <=> JSON conversions are lossless.
pub struct File {
    h: Header,
    o: Objects,
    f: Fields,
    dat: Data,
}

macro_rules! check_offset {
    ($rd:expr, $exp:expr) => {{
        let pos = $rd.stream_position()?;
        if pos != $exp {
            return Err(FromBinError::OffsetMismatch { exp: $exp, is: pos });
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

    /// Attempt create a Darkest Dungeon save [`File`] from a [`Read`] representing
    /// a binary encoded file.
    pub fn try_from_bin<R: Read + Seek>(reader: &'_ mut R) -> Result<Self, FromBinError> {
        let h = Header::try_from_bin(reader)?;
        check_offset!(reader, u64::from(h.objects_offset));
        let o: Objects = Objects::try_from_bin(reader, &h)?;

        check_offset!(reader, u64::from(h.fields_offset));
        let mut f = Fields::try_from_bin(reader, &h)?;

        check_offset!(reader, u64::from(h.data_offset));
        let dat = parts::decode_fields(reader, &mut f, &o, &h)?;
        Ok(File { h, o, f, dat })
    }

    /// Attempt to decode a [`File`] from a [`Read`] representing a JSON stream.
    pub fn try_from_json<R: Read>(reader: &'_ mut R) -> Result<Self, FromJsonError> {
        let mut x = vec![];
        reader.read_to_end(&mut x)?;
        let slic = std::str::from_utf8(&x)?;

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
            dat: Default::default(),
        };

        lex.expect(TokenType::BeginObject, true)?;

        let vers_field_tok = lex.expect(TokenType::FieldName, true)?;
        if vers_field_tok.dat != Self::BUILTIN_VERSION_FIELD {
            return Err(FromJsonError::Expected(
                Self::BUILTIN_VERSION_FIELD.to_owned(),
                vers_field_tok.loc.first,
                vers_field_tok.loc.end,
            ));
        }
        let vers = lex.expect(TokenType::Number, true)?;
        let vers_num: u32 = vers.dat.parse::<u32>().map_err(|_| {
            FromJsonError::Expected(
                format!("{:?}", TokenType::Number),
                vers.loc.first,
                vers.loc.end,
            )
        })?;

        let mut name_stack = vec![];

        s.read_child_fields(None, &mut name_stack, lex)?;
        let data_size = s.fixup_offsets()?;
        s.h.fixup_header(s.o.len(), s.f.len(), vers_num, data_size)?;
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
        let field_index = self.f.create_field(name).ok_or(FromJsonError::IntegerErr)?;
        // Identify type
        name_stack.push(name.to_owned());
        let val = match lex.peek().ok_or(FromJsonError::UnexpEOF)?.as_ref()?.kind {
            TokenType::BeginObject => {
                if name == "raw_data" || name == "static_save" {
                    let inner = File::try_from_json_parser(lex)?;
                    Some(FieldType::File(Some(Box::new(inner))))
                } else {
                    lex.next();
                    self.dat
                        .create_data(name, parent, FieldType::Object(vec![]));
                    let obj_index = self
                        .o
                        .create_object(field_index, parent, 0, 0)
                        .ok_or(FromJsonError::IntegerErr)?;
                    self.f[field_index].field_info |= 1;
                    self.f[field_index].field_info |=
                        (obj_index.numeric() & FieldInfo::OBJ_IDX_BITS) << 11;

                    // Recursion here
                    let childs = self.read_child_fields(Some(obj_index), name_stack, lex)?;

                    self.o[obj_index].num_direct_childs = childs.len() as u32;
                    self.o[obj_index].num_all_childs = self.f.len() - 1 - field_index.numeric();
                    if let FieldType::Object(ref mut chl) = self.dat[field_index].tipe {
                        *chl = childs;
                    } else {
                        unreachable!("pushed obj earlier");
                    }
                    None
                }
            }
            _ => Some(FieldType::try_from_json(lex, &name_stack)?),
        };
        if let Some(val) = val {
            self.dat.create_data(name, parent, val);
        }
        name_stack.pop();
        Ok(field_index)
    }

    /// Write this [`File`] as JSON.
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
            self.h.version()
        ))?;
        if let Some(root) = self.o.iter().find(|o| o.parent.is_none()) {
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
        let dat = &self.dat[field_idx];
        if let FieldType::Object(ref c) = dat.tipe {
            if c.is_empty() {
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
                        if !emitted_fields.insert(&self.dat[child].name) {
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
            unreachable!("is object")
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
        let dat = &self.dat[field_idx];
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
        for f in self.dat.iter() {
            existing_size = f.add_bin_size(existing_size);
        }
        meta_size + existing_size
    }

    fn fixup_offsets(&mut self) -> Result<u32, std::num::TryFromIntError> {
        let mut offset = 0;
        for (idx, f) in self.f.iter_mut() {
            f.offset = u32::try_from(offset)?;
            offset = self.dat[idx].add_bin_size(offset);
        }
        Ok(u32::try_from(offset)?)
    }

    /// Write this [`File`] as Binary.
    pub fn write_to_bin<W: Write>(&self, writer: &'_ mut W) -> std::io::Result<()> {
        self.h.write_to_bin(writer)?;
        self.o.write_to_bin(writer)?;
        self.f.write_to_bin(writer)?;
        let mut off = 0;
        for f in self.dat.iter() {
            off = f.write_to_bin(writer, off)?;
        }
        Ok(())
    }
}
