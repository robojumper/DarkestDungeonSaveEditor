use byteorder::{LittleEndian, ReadBytesExt, WriteBytesExt};
use std::{
    collections::HashMap,
    convert::TryFrom,
    ffi::CStr,
    io::{Read, Write},
    ops::{Index, IndexMut},
};

use crate::{
    err::*,
    file::{
        json::{ExpectExt, Token, TokenType},
        File,
    },
    util::name_hash,
};

pub const HEADER_MAGIC_NUMBER: [u8; 4] = [0x01, 0xB1, 0x00, 0x00];
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Header {
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
    pub const SIZE: usize = 64;

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

        if magic != HEADER_MAGIC_NUMBER {
            return Err(FromBinError::NotBinFile);
        }

        let version = reader.read_u32::<LittleEndian>().unwrap();
        let header_len = reader.read_u32::<LittleEndian>().unwrap();
        if header_len != u32::try_from(Header::SIZE).unwrap() {
            return Err(FromBinError::OffsetMismatch {
                exp: u64::try_from(Header::SIZE).unwrap(),
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
        let buf = &mut [0u8; Header::SIZE];
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

    pub fn fixup_header(
        &mut self,
        num_objects: u32,
        num_fields: u32,
        version: u32,
        data_size: u32,
    ) -> Result<(), std::num::TryFromIntError> {
        self.magic = HEADER_MAGIC_NUMBER;
        self.version = version;
        self.header_len = u32::try_from(Header::SIZE).unwrap();
        self.objects_num = num_objects;
        self.objects_size = self.objects_num * 16; // TODO
        self.objects_offset = self.header_len;
        self.fields_num = num_fields;
        self.fields_offset = self.objects_offset + self.objects_size; // TODO
        self.data_offset = self.fields_offset + self.fields_num * 12; // TODO
        self.data_size = data_size;
        Ok(())
    }

    pub fn version(&self) -> u32 {
        self.version
    }

    pub fn calc_bin_size(&self) -> usize {
        Header::SIZE
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ObjectInfo {
    pub parent: Option<ObjIdx>,
    pub field: FieldIdx,
    pub num_direct_childs: u32,
    pub num_all_childs: u32,
}
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct Objects {
    objs: Vec<ObjectInfo>,
}

impl Objects {
    pub fn iter(&self) -> impl Iterator<Item = &ObjectInfo> {
        self.objs.iter()
    }

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
                .write_i32::<LittleEndian>(o.parent.map(|i| i.0 as i32).unwrap_or(-1))
                .unwrap();
            writer.write_u32::<LittleEndian>(o.field.0).unwrap();
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

    // Parent Object indices are i32 because a -1 indicates no parent
    pub fn len(&self) -> u32 {
        self.objs.len() as u32
    }

    pub fn create_object(
        &mut self,
        f: FieldIdx,
        p: Option<ObjIdx>,
        nc: u32,
        ndc: u32,
    ) -> Option<ObjIdx> {
        if self.len() == u32::try_from(std::i32::MAX).unwrap() {
            None
        } else {
            let idx = self.len();
            self.objs.push(ObjectInfo {
                field: f,
                parent: p,
                num_all_childs: nc,
                num_direct_childs: ndc,
            });
            Some(ObjIdx(idx))
        }
    }
}

impl Index<ObjIdx> for Objects {
    type Output = ObjectInfo;
    #[inline]
    fn index(&self, index: ObjIdx) -> &Self::Output {
        &self.objs[index.0 as usize]
    }
}

impl IndexMut<ObjIdx> for Objects {
    #[inline]
    fn index_mut(&mut self, index: ObjIdx) -> &mut Self::Output {
        &mut self.objs[index.0 as usize]
    }
}
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Fields {
    fields: Vec<FieldInfo>,
}
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FieldInfo {
    pub name_hash: i32,
    pub offset: u32,
    pub field_info: u32,
}

/// Represents the object index. Always less than `i32::MAX`.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub struct ObjIdx(u32);

impl ObjIdx {
    /// Returns the numeric value of this object index
    pub fn numeric(self) -> u32 {
        self.0 as u32
    }
}
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub struct FieldIdx(u32);

impl FieldIdx {
    /// Returns the numeric value of this field index
    pub fn numeric(self) -> u32 {
        self.0
    }
}

impl Fields {
    #[allow(unused)]
    pub fn iter(&self) -> impl Iterator<Item = (FieldIdx, &FieldInfo)> {
        self.fields
            .iter()
            .enumerate()
            .map(|(f, a)| (FieldIdx(f as u32), a))
    }

    pub fn iter_mut(&mut self) -> impl Iterator<Item = (FieldIdx, &mut FieldInfo)> {
        self.fields
            .iter_mut()
            .enumerate()
            .map(|(f, a)| (FieldIdx(f as u32), a))
    }

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

    pub fn create_field(&mut self, name: &str) -> Option<FieldIdx> {
        if self.len() == std::u32::MAX {
            None
        } else {
            let idx = self.len();
            self.fields.push(FieldInfo {
                name_hash: name_hash(name),
                field_info: {
                    let len = u32::try_from(name.len() + 1).ok()?;
                    (len & FieldInfo::NAME_LEN_BITS) << 2
                },
                offset: 0,
            });
            Some(FieldIdx(idx))
        }
    }

    pub fn len(&self) -> u32 {
        self.fields.len() as u32
    }

    pub fn calc_bin_size(&self) -> usize {
        4 * 3 * self.fields.len()
    }
}

impl FieldInfo {
    pub const NAME_LEN_BITS: u32 = 0b1_1111_1111;
    pub const OBJ_IDX_BITS: u32 = 0b1111_1111_1111_1111_1111;

    pub fn is_object(&self) -> bool {
        (self.field_info & 0b1) == 1
    }

    pub fn name_length(&self) -> u32 {
        (self.field_info >> 2) & Self::NAME_LEN_BITS
    }

    pub fn object_index(&self) -> Option<ObjIdx> {
        if self.is_object() {
            Some(ObjIdx((self.field_info >> 11) & Self::OBJ_IDX_BITS))
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

impl Index<FieldIdx> for Fields {
    type Output = FieldInfo;
    #[inline]
    fn index(&self, index: FieldIdx) -> &Self::Output {
        &self.fields[index.0 as usize]
    }
}

impl IndexMut<FieldIdx> for Fields {
    #[inline]
    fn index_mut(&mut self, index: FieldIdx) -> &mut Self::Output {
        &mut self.fields[index.0 as usize]
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Field {
    pub name: String,
    pub parent: Option<ObjIdx>,
    pub tipe: FieldType,
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

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Data {
    dat: Vec<Field>,
}

impl Index<FieldIdx> for Data {
    type Output = Field;
    #[inline]
    fn index(&self, index: FieldIdx) -> &Self::Output {
        &self.dat[index.0 as usize]
    }
}

impl IndexMut<FieldIdx> for Data {
    #[inline]
    fn index_mut(&mut self, index: FieldIdx) -> &mut Self::Output {
        &mut self.dat[index.0 as usize]
    }
}

impl Data {
    pub fn iter(&self) -> impl Iterator<Item = &Field> {
        self.dat.iter()
    }

    pub fn create_data(
        &mut self,
        name: std::borrow::Cow<'_, str>,
        parent: Option<ObjIdx>,
        tipe: FieldType,
    ) {
        self.dat.push(Field {
            name: name.into_owned(),
            parent,
            tipe,
        });
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

macro_rules! types {
    ($types:ident, $([$e:ident, $($i:literal),+]), + $(,)*) => {
        const $types: &[(&FieldType, &[&str])] = &[$((types!($e), &[$($i,)+]),)+];
    };
    (Float) => {&FieldType::Float(0.0)};
    (Char) => {&FieldType::Char('\0')};
    (IntVector) => {&FieldType::IntVector(std::vec::Vec::new())};
    (StringVector) => {&FieldType::StringVector(std::vec::Vec::new())};
    (FloatArray) => {&FieldType::FloatArray(std::vec::Vec::new())};
    (TwoInt) => {&FieldType::TwoInt(0, 0)};
    (TwoBool) => {&FieldType::TwoBool(false, false)};
}

#[rustfmt::skip]
types!(TYPES,
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

    [TwoBool, "profile_options", "values", "quest_select_warnings"],
    [TwoBool, "profile_options", "values", "provision_warnings"],
    [TwoBool, "profile_options", "values", "deck_based_stage_coach"],
    [TwoBool, "profile_options", "values", "curio_tracker"],
    [TwoBool, "profile_options", "values", "dd_mode"],
    [TwoBool, "profile_options", "values", "corpses"],
    [TwoBool, "profile_options", "values", "stall_penalty"],
    [TwoBool, "profile_options", "values", "deaths_door_recovery_debuffs"],
    [TwoBool, "profile_options", "values", "retreats_can_fail"],
    [TwoBool, "profile_options", "values", "multiplied_enemy_crits"],
);

pub fn hardcoded_type(parents: &'_ [impl AsRef<str>], name: impl AsRef<str>) -> Option<FieldType> {
    use once_cell::sync::OnceCell;
    static TYPES_MAP: OnceCell<HashMap<&str, Vec<(&[&str], &FieldType)>>> = OnceCell::new();
    if let Some(candidates) = TYPES_MAP
        .get_or_init(|| {
            let mut map = HashMap::new();
            TYPES.iter().for_each(|(tip, trace)| {
                map.entry(*trace.last().unwrap())
                    .or_insert_with(Vec::new)
                    .push((&trace[0..trace.len() - 1], *tip));
            });
            map
        })
        .get(name.as_ref())
    {
        candidates.iter().find_map(|(path, t)| {
            if parents.len() >= path.len()
                && path
                    .iter()
                    .rev()
                    .zip(parents.iter().rev())
                    .all(|(tst, name_frag)| tst == &"*" || tst == &name_frag.as_ref())
            {
                Some((*t).clone())
            } else {
                None
            }
        })
    } else {
        None
    }
}

macro_rules! skip {
    ($r:expr, $num:expr) => {
        std::io::copy(&mut $r.by_ref().take($num as u64), &mut std::io::sink())?;
    };
}

pub fn decode_fields<R: Read>(
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
    let mut obj_names: Vec<String> = vec![];
    for (idx, field) in f.fields.iter().enumerate() {
        // Read name
        let off = field.offset as usize;
        let len = field.name_length() as usize;
        let field_name = buf
            .get(off..off + len)
            .ok_or(FromBinError::SizeMismatch { at: off, exp: len })?;
        let name = {
            let cs = CStr::from_bytes_with_nul(&field_name)?.to_str()?;
            let mut n = String::new();
            n.try_reserve_exact(cs.len())?;
            n.push_str(cs);
            n
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
    pub(crate) fn try_from_json<'a, T: Iterator<Item = Result<Token<'a>, JsonError>>>(
        lex: &mut T,
        name_stack: &'_ [impl AsRef<str>],
        name: impl AsRef<str>,
    ) -> Result<Self, FromJsonError> {
        macro_rules! parse_prim {
            ($tok:expr, $t:ty, $err:expr) => {{
                let tok = $tok;
                tok.dat.parse::<$t>().map_err(|_| {
                    FromJsonError::LiteralFormat($err.to_owned(), tok.span.first, tok.span.end)
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
                    loop {
                        let tok = lex.next().ok_or(FromJsonError::UnexpEOF)??;
                        match tok.kind {
                            TokenType::EndArray => break,
                            TokenType::Number => {
                                v.push(parse_prim!(tok, i32, "integer"));
                            }
                            TokenType::String if tok.dat.starts_with("###") => {
                                v.push(name_hash(&tok.dat[3..]));
                            }
                            _ => {
                                return Err(FromJsonError::Expected(
                                    "string or ]".to_owned(),
                                    tok.span.first,
                                    tok.span.end,
                                ))
                            }
                        }
                    }
                }
                FieldType::StringVector(ref mut v) => {
                    lex.expect(TokenType::BeginArray)?;
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
                                    tok.span.first,
                                    tok.span.end,
                                ))
                            }
                        }
                    }
                }
                FieldType::FloatArray(ref mut v) => {
                    lex.expect(TokenType::BeginArray)?;
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
                                    tok.span.first,
                                    tok.span.end,
                                ))
                            }
                        }
                    }
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
                            tok1.span.first,
                            tok1.span.end,
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
                            tok2.span.first,
                            tok2.span.end,
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
                            tok.span.first,
                            tok.span.end,
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
                        FieldType::String(parse_prim!(tok, String, "string"))
                    }
                }
                TokenType::BoolTrue => FieldType::Bool(true),
                TokenType::BoolFalse => FieldType::Bool(false),
                _ => {
                    return Err(FromJsonError::LiteralFormat(
                        "unknown field".to_owned(),
                        tok.span.first,
                        tok.span.end,
                    ))
                }
            })
        }
    }

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
                        if len >= 4 && buf[0..4] == HEADER_MAGIC_NUMBER {
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
