use byteorder::{LittleEndian, ReadBytesExt, WriteBytesExt};
use std::{
    collections::HashMap,
    convert::TryFrom,
    ffi::CStr,
    io::{Read, Write},
};

use super::{
    hardcoded_type, Data, Field, FieldIdx, FieldInfo, FieldType, Fields, File, Header, NameType,
    ObjIdx, ObjectInfo, Objects,
};
use crate::{err::*, util::name_hash};

impl File {
    /// Attempt create a Darkest Dungeon save [`File`] from a [`Read`] representing
    /// a binary encoded file.
    pub fn try_from_bin<R: Read>(mut reader: R) -> Result<Self, FromBinError> {
        let reader = reader.by_ref();
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

    pub(super) fn fixup_offsets(&mut self) -> Result<u32, std::num::TryFromIntError> {
        let mut offset = 0;
        for (idx, f) in self.f.iter_mut() {
            f.offset = u32::try_from(offset)?;
            offset = self.dat[idx].add_bin_size(offset);
        }
        u32::try_from(offset)
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
        let calc_offset = objects_offset
            .checked_add(objects_num.checked_mul(16).ok_or(FromBinError::Arith)?)
            .ok_or(FromBinError::Arith)?;
        if fields_offset != calc_offset {
            return Err(FromBinError::OffsetMismatch {
                exp: fields_offset.into(),
                is: calc_offset.into(),
            });
        }
        let _ = reader.read_u32::<LittleEndian>().unwrap();
        let data_size = reader.read_u32::<LittleEndian>().unwrap();
        let data_offset = reader.read_u32::<LittleEndian>().unwrap();
        let calc_offset = fields_offset
            .checked_add(fields_num.checked_mul(12).ok_or(FromBinError::Arith)?)
            .ok_or(FromBinError::Arith)?;
        if data_offset != calc_offset {
            return Err(FromBinError::OffsetMismatch {
                exp: data_offset.into(),
                is: calc_offset.into(),
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

    fn calc_bin_size(&self) -> usize {
        Header::BIN_SIZE
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

    fn calc_bin_size(&self) -> usize {
        4 * 4 * self.objs.len() // TODO
    }
}

impl Fields {
    fn try_from_bin<R: Read>(reader: &'_ mut R, header: &Header) -> Result<Self, FromBinError> {
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

    fn write_to_bin<W: Write>(&self, inwriter: &'_ mut W) -> std::io::Result<()> {
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

    fn calc_bin_size(&self) -> usize {
        4 * 3 * self.fields.len() // TODO
    }
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
                for s in v.iter() {
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
    fn write_to_bin<W: Write>(
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
                for &i in v.iter() {
                    inwriter.write_i32::<LittleEndian>(i)?;
                }
                align + 4 + v.len() * 4
            }
            StringVector(ref v) => {
                inwriter.write_all(align_zeros)?;
                inwriter.write_u32::<LittleEndian>(v.len() as u32)?;
                let mut tmp_size = 4;
                let any_zeroes = &[0u8; 4];
                for s in v.iter() {
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
                for &f in v.iter() {
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

/// Helper struct for decoding binary objects according to their
/// number of direct children
struct BinObjectFrame {
    obj_idx: ObjIdx,
    field_idx: FieldIdx,
    num_childs: u32,
    name: NameType,
}

impl AsRef<str> for BinObjectFrame {
    fn as_ref(&self) -> &str {
        self.name.as_ref()
    }
}

fn decode_fields_bin<R: Read>(
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
    let mut obj_stack: Vec<BinObjectFrame> = vec![];
    for (idx, field) in f.fields.iter().enumerate() {
        // Read name
        let off = field.offset as usize;
        let len = field.name_length() as usize;
        let field_name = buf.get(off..off + len).ok_or(FromBinError::Eof)?;
        let name = {
            let cs = CStr::from_bytes_with_nul(&field_name)?.to_str()?;
            NameType::from(cs)
        };

        if name_hash(&name) != field.name_hash {
            return Err(FromBinError::HashMismatch);
        }

        let data_begin = off + len;
        let data_end = off + offset_sizes[&(field.offset as usize)]; // exclusive
        let field_type = if let Some(obj_idx) = field.object_index() {
            let num_childs = o[obj_idx].num_direct_childs;
            FieldType::Object(vec![FieldIdx::dangling(); num_childs as usize].into_boxed_slice())
        } else {
            if data_end <= data_begin {
                return Err(FromBinError::FormatErr);
            }
            let to_skip_if_aligned = ((data_begin + 3) & !0b11) - data_begin;
            let mut field_data = buf.get(data_begin..data_end).ok_or(FromBinError::Eof)?;
            FieldType::try_from_bin(
                &mut field_data,
                to_skip_if_aligned,
                data_end - data_begin,
                &obj_stack,
                &name,
            )?
        };
        data.dat.push(self::Field {
            name: name.clone(),
            parent: obj_stack.last().map(|f| f.obj_idx),
            tipe: field_type,
        });

        if let Some(frame) = obj_stack.last_mut() {
            let childs = data[frame.field_idx].tipe.unwrap_object_mut();
            childs[frame.num_childs as usize] = FieldIdx(idx as u32);
            frame.num_childs += 1;
        } else if !field.is_object() {
            return Err(FromBinError::MissingRoot);
        }

        if let Some(obj_idx) = field.object_index() {
            let field_idx = o[obj_idx].field;
            match data.get(field_idx) {
                Some(Field {
                    tipe: FieldType::Object(_),
                    ..
                }) => {}
                _ => return Err(FromBinError::FormatErr),
            }
            obj_stack.push(BinObjectFrame {
                obj_idx,
                field_idx,
                num_childs: 0,
                name: name.clone(),
            });
        }

        while let Some(frame) = obj_stack.last() {
            if frame.num_childs == o[frame.obj_idx].num_direct_childs {
                obj_stack.pop();
            } else {
                break;
            }
        }
    }

    Ok(data)
}

impl FieldType {
    fn try_from_bin<R: Read>(
        reader: &'_ mut R,
        to_skip_if_aligned: usize,
        max_len: usize,
        name_trace: &'_ [impl AsRef<str>],
        name: &'_ str,
    ) -> Result<Self, FromBinError> {
        use FieldType::*;

        // This could be detected heuristically, but chances are we'll incorrectly re-encode
        // them because they'll look like plain objects to the JSON decoder.
        // Also, this doesn't use `hardcoded_type` because we'd have to pay for that lookup
        // on every object when decoding from JSON.
        if name == "raw_data" || name == "static_save" {
            skip!(reader, to_skip_if_aligned);
            let len = reader.read_i32::<LittleEndian>()? as usize;
            if len.checked_add(4).ok_or(FromBinError::Arith)? == max_len - to_skip_if_aligned {
                Ok(FieldType::File(Some(Box::new(self::File::try_from_bin(
                    reader.by_ref(),
                )?))))
            } else {
                Err(FromBinError::UnknownField(name.to_owned()))
            }
        } else if let Some(mut val) = hardcoded_type(name_trace, name) {
            match &mut val {
                Float(ref mut f) => {
                    skip!(reader, to_skip_if_aligned);
                    *f = reader.read_f32::<LittleEndian>()?;
                }
                IntVector(ref mut v) => {
                    skip!(reader, to_skip_if_aligned);
                    let num = reader.read_u32::<LittleEndian>()? as usize;
                    let mut tmp_vec = vec![];
                    tmp_vec.try_reserve_exact(num)?;
                    for _ in 0..num {
                        tmp_vec.push(reader.read_i32::<LittleEndian>()?);
                    }
                    *v = tmp_vec.into_boxed_slice();
                }
                StringVector(ref mut v) => {
                    skip!(reader, to_skip_if_aligned);
                    let num = reader.read_u32::<LittleEndian>()? as usize;
                    let mut tmp_vec = vec![];
                    tmp_vec.try_reserve_exact(num)?;
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
                        tmp_vec.push(string);
                        to_skip = ((len + 3) & !0b11) - len;
                    }
                    *v = tmp_vec.into_boxed_slice();
                }
                FloatArray(ref mut v) => {
                    skip!(reader, to_skip_if_aligned);
                    let num = (max_len - to_skip_if_aligned) / 4;
                    let mut tmp_vec = vec![];
                    tmp_vec.try_reserve_exact(num)?;
                    for _ in 0..num {
                        tmp_vec.push(reader.read_f32::<LittleEndian>()?);
                    }
                    *v = tmp_vec.into_boxed_slice();
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
                match aligned_max_len {
                    4 => Ok(Int(reader.read_i32::<LittleEndian>()?)),
                    5..=usize::MAX => {
                        let len = reader.read_i32::<LittleEndian>()? as usize;
                        if len.checked_add(4).ok_or(FromBinError::Arith)? == aligned_max_len {
                            let mut buf = vec![0u8; len];
                            reader.read_exact(&mut buf)?;
                            let string = {
                                let cs = CStr::from_bytes_with_nul(&buf)?.to_str()?;
                                let mut n = std::string::String::new();
                                n.try_reserve_exact(cs.len())?;
                                n.push_str(cs);
                                n
                            };
                            Ok(String(string.into_boxed_str()))
                        } else {
                            Err(FromBinError::UnknownField(name.to_owned()))
                        }
                    }
                    _ => Err(FromBinError::UnknownField(name.to_owned())),
                }
            }
        }
    }
}
