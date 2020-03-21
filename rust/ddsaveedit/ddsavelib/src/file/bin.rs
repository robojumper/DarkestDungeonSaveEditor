use byteorder::{LittleEndian, ReadBytesExt, WriteBytesExt};
use std::{
    collections::HashMap,
    convert::TryFrom,
    ffi::CStr,
    io::{Read, Write},
};

use super::{
    hardcoded_type, Data, Field, FieldIdx, FieldInfo, FieldType, Fields, File, Header, ObjIdx,
    ObjectInfo, Objects,
};
use crate::{err::*, util::name_hash};

use string_cache::{Atom, EmptyStaticAtomSet};

impl File {
    /// Attempt create a Darkest Dungeon save [`File`] from a [`Read`] representing
    /// a binary encoded file.
    pub fn try_from_bin<R: Read>(reader: &'_ mut R) -> Result<Self, FromBinError> {
        let h = Header::try_from_bin(reader)?;
        let o: Objects = Objects::try_from_bin(reader, &h)?;
        let mut f = Fields::try_from_bin(reader, &h)?;
        let dat = decode_fields_bin(reader, &mut f, &o, &h)?;
        Ok(File { h, o, f, dat })
    }

    fn calc_bin_size(&self) -> usize {
        let meta_size = self.h.calc_bin_size() + self.o.calc_bin_size() + self.f.calc_bin_size();
        let mut existing_size = 0;
        for f in self.dat.iter() {
            existing_size = f.add_bin_size(existing_size);
        }
        meta_size + existing_size
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

impl Header {
    pub fn try_from_bin<R: Read>(reader: &'_ mut R) -> Result<Self, FromBinError> {
        // Reading all 64 header bytes upfront elides all the checks in later unwraps/?s.
        let buf = &mut [0u8; Header::BIN_SIZE];
        reader
            .read_exact(buf)
            .map_err(|_| FromBinError::NotBinFile)?;
        let mut reader: &[u8] = buf;

        let magic = {
            let tmp = &mut [0u8; 4];
            reader.read_exact(tmp).unwrap();
            *tmp
        };

        if magic != Header::MAGIC_NUMBER {
            return Err(FromBinError::NotBinFile);
        }

        let version = reader.read_u32::<LittleEndian>().unwrap();
        let header_len = reader.read_u32::<LittleEndian>().unwrap();
        if header_len != u32::try_from(Header::BIN_SIZE).unwrap() {
            return Err(FromBinError::OffsetMismatch {
                exp: u64::try_from(Header::BIN_SIZE).unwrap(),
                is: header_len.into(),
            });
        }
        let _ = reader.read_u32::<LittleEndian>().unwrap(); // Zeroes
        let objects_size = reader.read_u32::<LittleEndian>().unwrap();
        let objects_num = reader.read_u32::<LittleEndian>().unwrap();
        let objects_offset = reader.read_u32::<LittleEndian>().unwrap();
        if objects_offset != header_len {
            return Err(FromBinError::OffsetMismatch {
                exp: header_len.into(),
                is: objects_offset.into(),
            });
        }
        let _ = reader.read_u64::<LittleEndian>().unwrap();
        let _ = reader.read_u64::<LittleEndian>().unwrap();
        let fields_num = reader.read_u32::<LittleEndian>().unwrap();
        let fields_offset = reader.read_u32::<LittleEndian>().unwrap();
        if fields_offset != objects_offset + objects_num * 16 {
            return Err(FromBinError::OffsetMismatch {
                exp: fields_offset.into(),
                is: (objects_offset + objects_num * 16).into(),
            });
        }
        let _ = reader.read_u32::<LittleEndian>().unwrap();
        let data_size = reader.read_u32::<LittleEndian>().unwrap();
        let data_offset = reader.read_u32::<LittleEndian>().unwrap();
        if data_offset != fields_offset + fields_num * 12 {
            return Err(FromBinError::OffsetMismatch {
                exp: data_offset.into(),
                is: (fields_offset + fields_num * 12).into(),
            });
        }
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

    pub fn write_to_bin<W: Write>(&self, inwriter: &'_ mut W) -> std::io::Result<()> {
        let buf = &mut [0u8; Header::BIN_SIZE];
        let mut writer: &mut [u8] = buf;

        writer.write_all(&self.magic)?;
        writer.write_u32::<LittleEndian>(self.version).unwrap();
        writer.write_u32::<LittleEndian>(self.header_len).unwrap();
        writer.write_u32::<LittleEndian>(0).unwrap(); // Zeroes
        writer.write_u32::<LittleEndian>(self.objects_size).unwrap();
        writer.write_u32::<LittleEndian>(self.objects_num).unwrap();
        writer
            .write_u32::<LittleEndian>(self.objects_offset)
            .unwrap();
        writer.write_u64::<LittleEndian>(0).unwrap();
        writer.write_u64::<LittleEndian>(0).unwrap();
        writer.write_u32::<LittleEndian>(self.fields_num).unwrap();
        writer
            .write_u32::<LittleEndian>(self.fields_offset)
            .unwrap();
        writer.write_u32::<LittleEndian>(0).unwrap();
        writer.write_u32::<LittleEndian>(self.data_size).unwrap();
        writer.write_u32::<LittleEndian>(self.data_offset).unwrap();

        inwriter.write_all(buf)?;
        Ok(())
    }
}

impl Objects {
    pub fn try_from_bin<R: Read>(reader: &'_ mut R, header: &Header) -> Result<Self, FromBinError> {
        let mut o = Objects {
            objs: {
                let mut v = vec![];
                v.try_reserve_exact(header.objects_num as usize)?;
                v
            },
        };
        let mut buf = vec![0u8; 4 * 4];
        for _ in 0..header.objects_num {
            reader.read_exact(&mut buf)?;
            let mut reader: &[u8] = &buf;
            let parent = reader.read_i32::<LittleEndian>().unwrap();
            let field = reader.read_u32::<LittleEndian>().unwrap();
            let num_direct_childs = reader.read_u32::<LittleEndian>().unwrap();
            let num_all_childs = reader.read_u32::<LittleEndian>().unwrap();
            o.objs.push(ObjectInfo {
                parent: if parent >= 0 {
                    Some(ObjIdx(parent as u32))
                } else {
                    None
                },
                field: FieldIdx(field),
                num_direct_childs,
                num_all_childs,
            })
        }
        Ok(o)
    }

    pub fn write_to_bin<W: Write>(&self, inwriter: &'_ mut W) -> std::io::Result<()> {
        let mut buf = vec![0u8; 4 * 4];
        for o in &self.objs {
            let mut writer: &mut [u8] = &mut buf;
            writer
                .write_i32::<LittleEndian>(o.parent.map(|i| i.numeric() as i32).unwrap_or(-1))
                .unwrap();
            writer.write_u32::<LittleEndian>(o.field.numeric()).unwrap();
            writer
                .write_u32::<LittleEndian>(o.num_direct_childs)
                .unwrap();
            writer.write_u32::<LittleEndian>(o.num_all_childs).unwrap();
            inwriter.write_all(&buf)?;
        }
        Ok(())
    }

    pub fn calc_bin_size(&self) -> usize {
        4 * 4 * self.objs.len()
    }
}

impl Fields {
    pub fn try_from_bin<R: Read>(reader: &'_ mut R, header: &Header) -> Result<Self, FromBinError> {
        let mut f = Fields {
            fields: {
                let mut v = vec![];
                v.try_reserve_exact(header.fields_num as usize)?;
                v
            },
        };
        let mut buf = vec![0u8; 3 * 4];
        for _ in 0..header.fields_num {
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

    pub fn write_to_bin<W: Write>(&self, inwriter: &'_ mut W) -> std::io::Result<()> {
        let mut buf = vec![0u8; 3 * 4];
        for o in &self.fields {
            let mut writer: &mut [u8] = &mut buf;
            writer.write_i32::<LittleEndian>(o.name_hash).unwrap();
            writer.write_u32::<LittleEndian>(o.offset).unwrap();
            writer.write_u32::<LittleEndian>(o.field_info).unwrap();
            inwriter.write_all(&buf)?;
        }
        Ok(())
    }

    pub fn calc_bin_size(&self) -> usize {
        4 * 3 * self.fields.len()
    }
}

impl Field {
    pub fn add_bin_size(&self, mut existing_size: usize) -> usize {
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

    /// Add bytes written
    pub fn write_to_bin<W: Write>(
        &self,
        inwriter: &'_ mut W,
        mut existing_offset: usize,
    ) -> std::io::Result<usize> {
        use FieldType::*;

        inwriter.write_all(self.name.as_bytes())?;
        inwriter.write_u8(0)?;
        existing_offset += self.name.len() + 1;
        let align = ((existing_offset + 3) & !0b11) - existing_offset;
        let align_zeros = &vec![0u8; align];

        existing_offset += match &self.tipe {
            Bool(b) => {
                inwriter.write_u8(if *b { 1u8 } else { 0u8 })?;
                1
            }
            TwoBool(b1, b2) => {
                inwriter.write_all(align_zeros)?;
                inwriter.write_u32::<LittleEndian>(if *b1 { 1 } else { 0 })?;
                inwriter.write_u32::<LittleEndian>(if *b2 { 1 } else { 0 })?;
                align + 8
            }
            Int(i) => {
                inwriter.write_all(align_zeros)?;
                inwriter.write_i32::<LittleEndian>(*i)?;
                align + 4
            }
            Float(f) => {
                inwriter.write_all(align_zeros)?;
                inwriter.write_f32::<LittleEndian>(*f)?;
                align + 4
            }
            Char(c) => {
                // ::Char constructed only with single byte char
                inwriter.write_u8(*c as u8)?;
                1
            }
            String(ref s) => {
                inwriter.write_all(align_zeros)?;
                inwriter.write_u32::<LittleEndian>(s.len() as u32 + 1)?;
                inwriter.write_all(s.as_bytes())?;
                inwriter.write_u8(0)?;
                align + 4 + s.len() + 1
            }
            IntVector(ref v) => {
                inwriter.write_all(align_zeros)?;
                inwriter.write_u32::<LittleEndian>(v.len() as u32)?;
                for &i in v {
                    inwriter.write_i32::<LittleEndian>(i)?;
                }
                align + 4 + v.len() * 4
            }
            StringVector(ref v) => {
                inwriter.write_all(align_zeros)?;
                inwriter.write_u32::<LittleEndian>(v.len() as u32)?;
                let mut tmp_size = 4;
                let any_zeroes = &[0u8; 4];
                for s in v {
                    let additional_align = ((tmp_size + 3) & !0b11) - tmp_size;
                    inwriter.write_all(&any_zeroes[0..additional_align])?;
                    inwriter.write_u32::<LittleEndian>(s.len() as u32 + 1)?;
                    inwriter.write_all(s.as_bytes())?;
                    inwriter.write_u8(0)?;
                    tmp_size += additional_align;
                    tmp_size += 4;
                    tmp_size += s.len() + 1;
                }
                tmp_size + align
            }
            FloatArray(ref v) => {
                inwriter.write_all(align_zeros)?;
                for &f in v {
                    inwriter.write_f32::<LittleEndian>(f)?;
                }
                align + 4 * v.len()
            }
            TwoInt(i1, i2) => {
                inwriter.write_all(align_zeros)?;
                inwriter.write_i32::<LittleEndian>(*i1)?;
                inwriter.write_i32::<LittleEndian>(*i2)?;
                align + 8
            }
            File(Some(ref f)) => {
                let mut v = vec![0u8; 0];
                f.write_to_bin(&mut v)?;
                inwriter.write_all(align_zeros)?;
                inwriter.write_u32::<LittleEndian>(v.len() as u32)?;
                inwriter.write_all(&v)?;
                align + 4 + v.len()
            }
            File(None) => unreachable!("file already en-/decoded"),
            Object(_) => 0,
        };

        Ok(existing_offset)
    }
}

macro_rules! skip {
    ($r:expr, $num:expr) => {
        std::io::copy(&mut $r.by_ref().take($num as u64), &mut std::io::sink())?;
    };
}

pub fn decode_fields_bin<R: Read>(
    reader: &'_ mut R,
    f: &'_ mut Fields,
    o: &'_ Objects,
    header: &Header,
) -> Result<Data, FromBinError> {
    let max_size = header.data_size as usize;
    let mut buf = vec![0; max_size];
    reader.read_exact(&mut buf)?;

    let mut offset_sizes = HashMap::<usize, usize>::new();
    let mut offsets = f
        .fields
        .iter()
        .map(|f| f.offset as usize)
        .collect::<Vec<_>>();
    offsets.sort_unstable();
    for offs in offsets.windows(2) {
        offset_sizes.insert(offs[0], offs[1] - offs[0]);
    }
    if let Some(&last) = offsets.last() {
        offset_sizes.insert(last, max_size.checked_sub(last).ok_or(FromBinError::Arith)?);
    }

    let mut data = Data { dat: vec![] };
    let mut obj_stack = vec![];
    let mut obj_nums = vec![];
    let mut obj_names: Vec<Atom<EmptyStaticAtomSet>> = vec![];
    for (idx, field) in f.fields.iter().enumerate() {
        // Read name
        let off = field.offset as usize;
        let len = field.name_length() as usize;
        let field_name = buf
            .get(off..off + len)
            .ok_or(FromBinError::SizeMismatch { at: off, exp: len })?;
        let name = {
            let cs = CStr::from_bytes_with_nul(&field_name)?.to_str()?;
            Atom::from(cs)
        };

        if name_hash(&name) != field.name_hash {
            return Err(FromBinError::HashMismatch);
        }

        let data_begin = off + len;
        let data_end = off + offset_sizes[&(field.offset as usize)]; // exclusive
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
                        exp: data_end - data_begin,
                    })?;
            FieldType::try_from_bin(
                &mut field_data,
                to_skip_if_aligned,
                data_end - data_begin,
                &obj_names,
                &name,
            )?
        };
        data.dat.push(self::Field {
            name: name.clone(),
            parent: obj_stack.last().copied(),
            tipe: field_type,
        });

        if obj_stack.is_empty() {
            if !field.is_object() {
                return Err(FromBinError::MissingRoot);
            }
        } else {
            if let FieldType::Object(ref mut v) = data[o[*obj_stack.last().unwrap()].field].tipe {
                v.push(FieldIdx(idx as u32))
            } else {
                return Err(FromBinError::FormatErr);
            }
            *obj_nums.last_mut().unwrap() += 1;
        }

        if field.is_object() {
            let idx = field.object_index().unwrap();
            if idx.0 >= o.len() {
                return Err(FromBinError::FormatErr);
            }
            obj_stack.push(field.object_index().unwrap());
            obj_nums.push(0u32);
            obj_names.push(name.clone());
        }

        while !obj_stack.is_empty()
            && *obj_nums.last().unwrap() == o[*obj_stack.last().unwrap()].num_direct_childs
        {
            obj_stack.pop();
            obj_nums.pop();
            obj_names.pop();
        }
    }

    Ok(data)
}

impl FieldType {
    pub fn try_from_bin<R: Read>(
        reader: &'_ mut R,
        to_skip_if_aligned: usize,
        max_len: usize,
        name_trace: &'_ [impl AsRef<str>],
        name: &'_ str,
    ) -> Result<Self, FromBinError> {
        use FieldType::*;

        if let Some(mut val) = hardcoded_type(name_trace, name) {
            match &mut val {
                Float(ref mut f) => {
                    skip!(reader, to_skip_if_aligned);
                    *f = reader.read_f32::<LittleEndian>()?;
                }
                IntVector(ref mut v) => {
                    skip!(reader, to_skip_if_aligned);
                    let num = reader.read_u32::<LittleEndian>()? as usize;
                    v.try_reserve_exact(num)?;
                    for _ in 0..num {
                        v.push(reader.read_i32::<LittleEndian>()?);
                    }
                }
                StringVector(ref mut v) => {
                    skip!(reader, to_skip_if_aligned);
                    let num = reader.read_u32::<LittleEndian>()? as usize;
                    v.try_reserve_exact(num)?;
                    let mut to_skip = 0;
                    for _ in 0..num {
                        skip!(reader, to_skip);
                        let len = reader.read_u32::<LittleEndian>()? as usize;
                        let mut buf = vec![0u8; len];
                        reader.read_exact(&mut buf)?;
                        let string = {
                            let cs = CStr::from_bytes_with_nul(&buf)?.to_str()?;
                            let mut n = std::string::String::new();
                            n.try_reserve_exact(cs.len())?;
                            n.push_str(cs);
                            n
                        };
                        v.push(string);
                        to_skip = ((len + 3) & !0b11) - len;
                    }
                }
                FloatArray(ref mut v) => {
                    skip!(reader, to_skip_if_aligned);
                    let num = (max_len - to_skip_if_aligned) / 4;
                    v.try_reserve_exact(num)?;
                    for _ in 0..num {
                        v.push(reader.read_f32::<LittleEndian>()?);
                    }
                }
                TwoInt(ref mut i1, ref mut i2) => {
                    skip!(reader, to_skip_if_aligned);
                    *i1 = reader.read_i32::<LittleEndian>()?;
                    *i2 = reader.read_i32::<LittleEndian>()?;
                }
                TwoBool(ref mut b1, ref mut b2) => {
                    skip!(reader, to_skip_if_aligned);
                    *b1 = reader.read_i32::<LittleEndian>()? != 0;
                    *b2 = reader.read_i32::<LittleEndian>()? != 0;
                }
                Char(ref mut c) => {
                    let b = &mut [0u8];
                    reader.read_exact(b)?;
                    if b[0].is_ascii() {
                        *c = char::from(b[0]);
                    } else {
                        return Err(FromBinError::CharError(b[0]));
                    }
                }
                _ => unreachable!("unhandled hardcoded type when reading"),
            }
            Ok(val)
        } else {
            // Use dynamic type ineference here
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
                    let len = reader.read_i32::<LittleEndian>()? as usize;
                    if len.checked_add(4).ok_or(FromBinError::FormatErr)? == aligned_max_len {
                        let mut buf = vec![0u8; len];
                        reader.read_exact(&mut buf)?;
                        let mut buf: &[u8] = &buf;
                        if len >= 4 && buf[0..4] == Header::MAGIC_NUMBER {
                            Ok(File(Some(Box::new(self::File::try_from_bin(&mut buf)?))))
                        } else {
                            let string = {
                                let cs = CStr::from_bytes_with_nul(&buf)?.to_str()?;
                                let mut n = std::string::String::new();
                                n.try_reserve_exact(cs.len())?;
                                n.push_str(cs);
                                n
                            };
                            Ok(String(string))
                        }
                    } else {
                        Err(FromBinError::UnknownField(
                            (*name_trace.last().unwrap()).as_ref().to_owned(),
                        ))
                    }
                }
            }
        }
    }
}
