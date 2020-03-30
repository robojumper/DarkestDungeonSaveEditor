use std::{
    borrow::Cow,
    convert::AsRef,
    io::{Read, Write},
};

use super::{hardcoded_type, FieldIdx, FieldInfo, FieldType, File, ObjIdx, Unhasher};
use crate::{
    err::*,
    util::{escape, name_hash},
};
use json_parser::{JsonError, Parser, TokenType};
use string_cache::{Atom, EmptyStaticAtomSet};

mod json_parser;

impl File {
    const BUILTIN_VERSION_FIELD: &'static str = "__revision_dont_touch";

    /// Attempt to decode a [`File`] from a [`Read`] representing a JSON stream.
    pub fn try_from_json<R: Read>(reader: &'_ mut R) -> Result<Self, FromJsonError> {
        let mut x = vec![];
        reader.read_to_end(&mut x)?;
        let slic = std::str::from_utf8(&x)?;

        let lex = &mut Parser::new(slic);
        Self::try_from_json_parser(lex, false)
    }

    fn read_version_field<'a>(lex: &mut Parser<'a>) -> Result<u32, FromJsonError> {
        let vers_field_tok = lex.expect(TokenType::FieldName)?;
        if vers_field_tok.dat != Self::BUILTIN_VERSION_FIELD {
            return Err(FromJsonError::Expected(
                Self::BUILTIN_VERSION_FIELD.to_owned(),
                lex.span().first,
                lex.span().end,
            ));
        }
        let vers = lex.expect(TokenType::Number)?;
        vers.dat.parse::<u32>().map_err(|_| {
            FromJsonError::Expected("Integer".to_owned(), lex.span().first, lex.span().end)
        })
    }

    fn try_from_json_parser<'a>(lex: &mut Parser<'a>, inner: bool) -> Result<Self, FromJsonError> {
        let mut s = Self {
            h: Default::default(),
            o: Default::default(),
            f: Default::default(),
            dat: Default::default(),
        };

        lex.expect(TokenType::BeginObject)?;

        let next_tok = lex.peek().ok_or(FromJsonError::UnexpEOF)?.as_ref()?;
        let vers_num = if next_tok.kind == TokenType::FieldName
            && next_tok.dat == Self::BUILTIN_VERSION_FIELD
        {
            Some(Self::read_version_field(lex)?)
        } else {
            None
        };

        let mut name_stack = vec![];

        // Expect a single "base_root" field
        let name = lex.expect(TokenType::FieldName)?.dat;
        s.read_field(name, None, &mut name_stack, lex)?;

        let vers_num = match vers_num {
            Some(n) => n,
            None => Self::read_version_field(lex)?,
        };

        lex.expect(TokenType::EndObject)?;

        if !inner {
            let next = lex.next();
            if let Some(Ok(_)) = next {
                return Err(FromJsonError::Expected(
                    "end of file".to_owned(),
                    lex.span().first,
                    lex.span().end,
                ));
            } else if let Some(Err(e)) = next {
                return Err(e.into());
            }
        }

        let data_size = s.fixup_offsets()?;
        s.h.fixup_header(s.o.len(), s.f.len(), vers_num, data_size)?;
        Ok(s)
    }

    fn read_child_fields<'a>(
        &mut self,
        parent: Option<ObjIdx>,
        name_stack: &mut Vec<Atom<EmptyStaticAtomSet>>,
        lex: &mut Parser<'a>,
    ) -> Result<Vec<FieldIdx>, FromJsonError> {
        let mut child_fields = vec![];

        loop {
            let tok = lex.next().ok_or(JsonError::EOF)??;
            match tok.kind {
                TokenType::EndObject => break,
                TokenType::FieldName => {
                    let name = tok.dat;
                    let idx = self.read_field(name, parent, name_stack, lex)?;
                    child_fields.push(idx);
                }
                _ => {
                    return Err(FromJsonError::Expected(
                        "name or }".to_owned(),
                        lex.span().first,
                        lex.span().end,
                    ))
                }
            }
        }

        Ok(child_fields)
    }

    fn read_field<'a>(
        &mut self,
        name: Cow<'a, str>,
        parent: Option<ObjIdx>,
        name_stack: &mut Vec<Atom<EmptyStaticAtomSet>>,
        lex: &mut Parser<'a>,
    ) -> Result<FieldIdx, FromJsonError> {
        let name = Atom::from(name.as_ref());
        let field_index = self
            .f
            .create_field(&name)
            .ok_or(FromJsonError::IntegerErr)?;
        // Identify type
        match lex.peek().ok_or(FromJsonError::UnexpEOF)?.as_ref()?.kind {
            TokenType::BeginObject => {
                if &name == "raw_data" || &name == "static_save" {
                    let inner = File::try_from_json_parser(lex, true)?;
                    self.dat
                        .create_data(name, parent, FieldType::File(Some(Box::new(inner))));
                } else {
                    lex.next();
                    self.dat
                        .create_data(name.clone(), parent, FieldType::Object(Box::new([])));
                    let obj_index = self
                        .o
                        .create_object(field_index, parent, 0, 0)
                        .ok_or(FromJsonError::IntegerErr)?;
                    self.f[field_index].field_info |= 1;
                    self.f[field_index].field_info |=
                        (obj_index.numeric() & FieldInfo::OBJ_IDX_BITS) << 11;

                    // Recursion here
                    name_stack.push(name);
                    let childs = self.read_child_fields(Some(obj_index), name_stack, lex)?;
                    name_stack.pop();

                    self.o[obj_index].num_direct_childs = childs.len() as u32;
                    self.o[obj_index].num_all_childs = self.f.len() - 1 - field_index.numeric();
                    if let FieldType::Object(ref mut chl) = self.dat[field_index].tipe {
                        *chl = childs.into_boxed_slice();
                    } else {
                        unreachable!("pushed obj earlier");
                    }
                }
            }
            _ => {
                let f = FieldType::try_from_json(lex, &name_stack, name.as_ref())?;
                self.dat.create_data(name, parent, f);
            }
        };
        Ok(field_index)
    }

    /// Write this [`File`] as JSON.
    pub fn write_to_json<T: AsRef<str>, W: Write>(
        &self,
        writer: &'_ mut W,
        allow_dupes: bool,
        unhash: &Unhasher<T>,
    ) -> std::io::Result<()> {
        self.write_to_json_priv(writer, &mut vec![], allow_dupes, unhash)
    }

    fn write_to_json_priv<T: AsRef<str>, W: Write>(
        &self,
        writer: &'_ mut W,
        indent: &mut Vec<u8>,
        allow_dupes: bool,
        unhash: &Unhasher<T>,
    ) -> std::io::Result<()> {
        writer.write_all(b"{\n")?;
        writer.write_all(&indent)?;
        writer.write_all(b"    ")?;
        writer.write_all(b"\"")?;
        writer.write_all(Self::BUILTIN_VERSION_FIELD.as_bytes())?;
        writer.write_all(b"\": ")?;
        itoa::write(writer.by_ref(), self.h.version())?;
        writer.write_all(b",\n")?;
        if let Some(root) = self.o.iter().find(|o| o.parent.is_none()) {
            indent.extend_from_slice(b"    ");
            self.write_field(root.field, writer, indent, false, allow_dupes, unhash)?;
            indent.truncate(indent.len() - 4);
        }
        writer.write_all(&indent)?;
        writer.write_all(b"}")?;
        Ok(())
    }

    fn write_object<T: AsRef<str>, W: Write>(
        &self,
        field_idx: FieldIdx,
        writer: &'_ mut W,
        indent: &mut Vec<u8>,
        comma: bool,
        allow_dupes: bool,
        unhash: &Unhasher<T>,
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
                    indent.extend_from_slice(b"    ");
                    self.write_field(
                        child,
                        writer,
                        indent,
                        idx != c.len() - 1,
                        allow_dupes,
                        unhash,
                    )?;
                    indent.truncate(indent.len() - 4);
                }
                writer.write_all(&indent)?;
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

    fn write_field<T: AsRef<str>, W: Write>(
        &self,
        field_idx: FieldIdx,
        writer: &'_ mut W,
        indent: &mut Vec<u8>,
        comma: bool,
        allow_dupes: bool,
        unhash: &Unhasher<T>,
    ) -> std::io::Result<()> {
        use FieldType::*;
        let dat = &self.dat[field_idx];
        writer.write_all(&indent)?;
        writer.write_all(b"\"")?;
        writer.write_all(dat.name.as_bytes())?;
        writer.write_all(b"\" : ")?;
        match &dat.tipe {
            Bool(b) => writer.write_all(if *b { b"true" } else { b"false" })?,
            TwoBool(b1, b2) => {
                writer.write_all(b"[")?;
                writer.write_all(if *b1 { b"true" } else { b"false" })?;
                writer.write_all(b", ")?;
                writer.write_all(if *b2 { b"true" } else { b"false" })?;
                writer.write_all(b"]")?;
            }
            Int(i) => match unhash.unhash(*i) {
                Some(s) => {
                    writer.write_all(b"\"")?;
                    writer.write_all(b"###")?;
                    writer.write_all(escape(s).as_bytes())?;
                    writer.write_all(b"\"")?;
                }
                None => {
                    itoa::write(writer.by_ref(), *i)?;
                }
            },
            Float(f) => {
                dtoa::write(writer.by_ref(), *f)?;
            }
            Char(c) => {
                writer.write_all(b"\"")?;
                let buf = [*c as u8];
                writer.write_all(&buf)?;
                writer.write_all(b"\"")?;
            }
            String(s) => {
                writer.write_all(b"\"")?;
                writer.write_all(escape(s).as_bytes())?;
                writer.write_all(b"\"")?;
            }
            IntVector(ref v) => {
                writer.write_all(b"[")?;
                for (idx, i) in v.iter().enumerate() {
                    match unhash.unhash(*i) {
                        Some(s) => {
                            writer.write_all(b"\"")?;
                            writer.write_all(b"###")?;
                            writer.write_all(escape(s).as_bytes())?;
                            writer.write_all(b"\"")?;
                        }
                        None => {
                            itoa::write(writer.by_ref(), *i)?;
                        }
                    }
                    if idx != v.len() - 1 {
                        writer.write_all(b", ")?;
                    }
                }
                writer.write_all(b"]")?;
            }
            StringVector(ref v) => {
                writer.write_all(b"[")?;
                for (idx, s) in v.iter().enumerate() {
                    writer.write_all(b"\"")?;
                    writer.write_all(escape(s).as_bytes())?;
                    writer.write_all(b"\"")?;
                    if idx != v.len() - 1 {
                        writer.write_all(b", ")?;
                    }
                }
                writer.write_all(b"]")?;
            }
            FloatArray(ref v) => {
                writer.write_all(b"[")?;
                for (idx, f) in v.iter().enumerate() {
                    dtoa::write(writer.by_ref(), *f)?;
                    if idx != v.len() - 1 {
                        writer.write_all(b", ")?;
                    }
                }
                writer.write_all(b"]")?;
            }
            TwoInt(i1, i2) => {
                writer.write_all(b"[")?;
                itoa::write(writer.by_ref(), *i1)?;
                writer.write_all(b", ")?;
                itoa::write(writer.by_ref(), *i2)?;
                writer.write_all(b"]")?;
            }
            File(ref obf) => {
                let fil = obf.as_ref().unwrap();
                fil.write_to_json_priv(writer, indent, allow_dupes, unhash)?;
            }
            Object(_) => {
                self.write_object(field_idx, writer, indent, comma, allow_dupes, unhash)?;
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
}

impl FieldType {
    fn try_from_json<'a>(
        lex: &mut Parser<'a>,
        name_stack: &'_ [impl AsRef<str>],
        name: impl AsRef<str>,
    ) -> Result<Self, FromJsonError> {
        macro_rules! parse_prim {
            ($tok:expr, $t:ty, $err:expr) => {{
                let tok = $tok;
                tok.dat.parse::<$t>().map_err(|_| {
                    FromJsonError::LiteralFormat($err.to_owned(), lex.span().first, lex.span().end)
                })?
            }};
        }

        if let Some(mut val) = hardcoded_type(name_stack, name) {
            match &mut val {
                FieldType::Float(ref mut f) => {
                    let tok = lex.expect(TokenType::Number)?;
                    *f = parse_prim!(tok, f32, "float");
                }
                FieldType::IntVector(ref mut v) => {
                    lex.expect(TokenType::BeginArray)?;
                    let mut tmp_vec = vec![];
                    loop {
                        let tok = lex.next().ok_or(FromJsonError::UnexpEOF)??;
                        match tok.kind {
                            TokenType::EndArray => break,
                            TokenType::Number => {
                                tmp_vec.push(parse_prim!(tok, i32, "integer"));
                            }
                            TokenType::String if tok.dat.starts_with("###") => {
                                tmp_vec.push(name_hash(&tok.dat[3..]));
                            }
                            _ => {
                                return Err(FromJsonError::Expected(
                                    "string or ]".to_owned(),
                                    lex.span().first,
                                    lex.span().end,
                                ))
                            }
                        }
                    }
                    *v = tmp_vec.into_boxed_slice();
                }
                FieldType::StringVector(ref mut v) => {
                    lex.expect(TokenType::BeginArray)?;
                    let mut tmp_vec = vec![];
                    loop {
                        let tok = lex.next().ok_or(FromJsonError::UnexpEOF)??;
                        match tok.kind {
                            TokenType::EndArray => break,
                            TokenType::String => {
                                tmp_vec.push(tok.dat.into_owned());
                            }
                            _ => {
                                return Err(FromJsonError::Expected(
                                    "string or ]".to_owned(),
                                    lex.span().first,
                                    lex.span().end,
                                ))
                            }
                        }
                    }
                    *v = tmp_vec.into_boxed_slice();
                }
                FieldType::FloatArray(ref mut v) => {
                    lex.expect(TokenType::BeginArray)?;
                    let mut tmp_vec = vec![];
                    loop {
                        let tok = lex.next().ok_or(FromJsonError::UnexpEOF)??;
                        match tok.kind {
                            TokenType::EndArray => break,
                            TokenType::Number => {
                                tmp_vec.push(parse_prim!(tok, f32, "float"));
                            }
                            _ => {
                                return Err(FromJsonError::Expected(
                                    "string or ]".to_owned(),
                                    lex.span().first,
                                    lex.span().end,
                                ))
                            }
                        }
                    }
                    *v = tmp_vec.into_boxed_slice();
                }
                FieldType::TwoInt(ref mut i1, ref mut i2) => {
                    lex.expect(TokenType::BeginArray)?;
                    let t1 = lex.expect(TokenType::Number)?;
                    *i1 = parse_prim!(t1, i32, "integer");
                    let t2 = lex.expect(TokenType::Number)?;
                    *i2 = parse_prim!(t2, i32, "integer");
                    lex.expect(TokenType::EndArray)?;
                }
                FieldType::TwoBool(ref mut b1, ref mut b2) => {
                    lex.expect(TokenType::BeginArray)?;
                    let tok1 = lex.next().ok_or(FromJsonError::UnexpEOF)??;
                    *b1 = if tok1.kind == TokenType::BoolTrue {
                        true
                    } else if tok1.kind == TokenType::BoolFalse {
                        false
                    } else {
                        return Err(FromJsonError::Expected(
                            "bool".to_owned(),
                            lex.span().first,
                            lex.span().end,
                        ));
                    };
                    let tok2 = lex.next().ok_or(FromJsonError::UnexpEOF)??;
                    *b2 = if tok2.kind == TokenType::BoolTrue {
                        true
                    } else if tok2.kind == TokenType::BoolFalse {
                        false
                    } else {
                        return Err(FromJsonError::Expected(
                            "bool".to_owned(),
                            lex.span().first,
                            lex.span().end,
                        ));
                    };
                    lex.expect(TokenType::EndArray)?;
                }
                FieldType::Char(ref mut c) => {
                    let tok = lex.expect(TokenType::String)?;
                    let bytes = tok.dat.as_bytes();
                    if bytes.len() == 1 && bytes[0].is_ascii() {
                        *c = bytes[0] as char;
                    } else {
                        return Err(FromJsonError::LiteralFormat(
                            "exactly one ascii char".to_owned(),
                            lex.span().first,
                            lex.span().end,
                        ));
                    }
                }
                _ => unreachable!("unhandled hardcoded field while from json"),
            }
            Ok(val)
        } else {
            // Decode heuristic
            let tok = lex.next().ok_or(FromJsonError::UnexpEOF)??;
            Ok(match tok.kind {
                TokenType::Number => FieldType::Int(parse_prim!(tok, i32, "integer")),
                TokenType::String => {
                    if tok.dat.starts_with("###") {
                        FieldType::Int(name_hash(&tok.dat[3..]))
                    } else {
                        FieldType::String(tok.dat.into_owned().into_boxed_str())
                    }
                }
                TokenType::BoolTrue => FieldType::Bool(true),
                TokenType::BoolFalse => FieldType::Bool(false),
                _ => {
                    return Err(FromJsonError::LiteralFormat(
                        "unknown field".to_owned(),
                        lex.span().first,
                        lex.span().end,
                    ))
                }
            })
        }
    }
}
