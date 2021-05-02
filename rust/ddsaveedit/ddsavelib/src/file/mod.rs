use std::{
    collections::HashMap,
    convert::{AsRef, TryFrom},
    ops::{Index, IndexMut},
};

use crate::util::name_hash;

#[cfg(feature = "string_cache")]
use string_cache::{Atom, EmptyStaticAtomSet};

mod bin;
mod json;

#[cfg(feature = "string_cache")]
type NameType = Atom<EmptyStaticAtomSet>;
#[cfg(not(feature = "string_cache"))]
type NameType = String;

/// A map from name hash -> name to make the JSON format more legible.
///
/// User-provided in [`File::write_to_json`].
#[derive(Debug, Default)]
pub struct Unhasher<T: AsRef<str>> {
    map: HashMap<i32, T>,
}

impl<T: AsRef<str>> Unhasher<T> {
    /// Create a new empty [`Unhasher`].
    pub fn new() -> Self {
        Self {
            map: HashMap::new(),
        }
    }

    /// Offer a single name that could potentially appear in a save file in hashed form.
    pub fn offer_name(&mut self, name: T) {
        self.map.insert(name_hash(name.as_ref()), name);
    }

    /// Offer names that could potentially appear in a save file in hashed form.
    pub fn offer_names<I: IntoIterator<Item = T>>(&mut self, names: I) {
        for name in names {
            self.map.insert(name_hash(name.as_ref()), name);
        }
    }

    fn unhash(&self, i: i32) -> Option<&str> {
        self.map.get(&i).map(|s| s.as_ref())
    }
}

impl Unhasher<&str> {
    /// Create an empty [`Unhasher`]. Shorthand for `Unhasher::<&str>::new()`.
    pub fn empty() -> Self {
        Self {
            map: HashMap::new(),
        }
    }
}

/// Represents a valid Darkest Dungeon save file.
///
/// See the [main library docs](`super`) for usage examples.
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
///
/// It is recommended that tools operating on the JSON representation preserve
/// the order of fields in the file.
#[derive(Clone, Debug, PartialEq)]
pub struct File {
    h: Header,
    o: Objects,
    f: Fields,
    dat: Data,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
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
    const BIN_SIZE: usize = 64;
    const MAGIC_NUMBER: [u8; 4] = [0x01, 0xB1, 0x00, 0x00];

    fn fixup_header(
        &mut self,
        num_objects: u32,
        num_fields: u32,
        version: u32,
        data_size: u32,
    ) -> Option<()> {
        self.magic = Self::MAGIC_NUMBER;
        self.version = version;
        self.header_len = u32::try_from(Header::BIN_SIZE).unwrap();
        self.objects_num = num_objects;
        self.objects_size = self.objects_num.checked_mul(16)?;
        self.objects_offset = self.header_len;
        self.fields_num = num_fields;
        self.fields_offset = self.objects_offset.checked_add(self.objects_size)?;
        self.data_offset = self
            .fields_offset
            .checked_add(self.fields_num.checked_mul(12)?)?;
        self.data_size = data_size;
        Some(())
    }

    fn version(&self) -> u32 {
        self.version
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
struct ObjectInfo {
    parent: Option<ObjIdx>,
    field: FieldIdx,
    num_direct_childs: u32,
    num_all_childs: u32,
}
#[derive(Clone, Debug, Default, Eq, PartialEq)]
struct Objects {
    objs: Vec<ObjectInfo>,
}

impl Objects {
    fn iter(&self) -> impl Iterator<Item = &ObjectInfo> {
        self.objs.iter()
    }

    // Parent Object indices are i32 because a -1 indicates no parent
    fn len(&self) -> u32 {
        self.objs.len() as u32
    }

    fn create_object(
        &mut self,
        f: FieldIdx,
        p: Option<ObjIdx>,
        nc: u32,
        ndc: u32,
    ) -> Option<ObjIdx> {
        // Overflow checks: We have a maximum of i32::MAX elements due to the binary format
        if self.len() == u32::try_from(std::i32::MAX).unwrap() {
            None
        } else {
            let idx = self.len();
            self.objs.try_reserve(1).ok()?;
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
struct Fields {
    fields: Vec<FieldInfo>,
}
#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct FieldInfo {
    name_hash: i32,
    offset: u32,
    field_info: u32,
}

/// Represents the object index. Always less than `i32::MAX`.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
struct ObjIdx(u32);

impl ObjIdx {
    /// Returns the numeric value of this object index
    fn numeric(self) -> u32 {
        self.0 as u32
    }
}
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
struct FieldIdx(u32);

impl FieldIdx {
    /// Returns the numeric value of this field index
    fn numeric(self) -> u32 {
        self.0
    }

    /// Create a dangling field index, to be replaced later
    fn dangling() -> Self {
        Self(u32::MAX)
    }
}

impl Fields {
    #[allow(unused)]
    fn iter(&self) -> impl Iterator<Item = (FieldIdx, &FieldInfo)> {
        self.fields
            .iter()
            .enumerate()
            .map(|(f, a)| (FieldIdx(f as u32), a))
    }

    fn iter_mut(&mut self) -> impl Iterator<Item = (FieldIdx, &mut FieldInfo)> {
        self.fields
            .iter_mut()
            .enumerate()
            .map(|(f, a)| (FieldIdx(f as u32), a))
    }

    fn create_field(&mut self, name: &str) -> Option<FieldIdx> {
        if self.len() == std::u32::MAX {
            None
        } else {
            let idx = self.len();
            self.fields.push(FieldInfo {
                name_hash: name_hash(name),
                field_info: {
                    let len = u32::try_from(name.len().checked_add(1)?).ok()?;
                    (len & FieldInfo::NAME_LEN_BITS) << 2
                },
                offset: 0,
            });
            Some(FieldIdx(idx))
        }
    }

    fn len(&self) -> u32 {
        self.fields.len() as u32
    }
}

impl FieldInfo {
    const NAME_LEN_BITS: u32 = 0b1_1111_1111;
    const OBJ_IDX_BITS: u32 = 0b1111_1111_1111_1111_1111;

    fn is_object(&self) -> bool {
        (self.field_info & 0b1) == 1
    }

    fn name_length(&self) -> u32 {
        (self.field_info >> 2) & Self::NAME_LEN_BITS
    }

    fn object_index(&self) -> Option<ObjIdx> {
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
struct Field {
    name: NameType,
    parent: Option<ObjIdx>,
    tipe: FieldType,
}

#[derive(Clone, Debug, Default, PartialEq)]
struct Data {
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
    fn iter(&self) -> impl Iterator<Item = &Field> {
        self.dat.iter()
    }

    fn get(&self, index: FieldIdx) -> Option<&<Self as Index<FieldIdx>>::Output> {
        self.dat.get(index.0 as usize)
    }

    fn create_data(&mut self, name: NameType, parent: Option<ObjIdx>, tipe: FieldType) {
        self.dat.push(Field { name, parent, tipe });
    }
}

#[derive(Clone, Debug, PartialEq)]
enum FieldType {
    Bool(bool),
    TwoBool(bool, bool),
    Int(i32),
    Float(f32),
    Char(char),
    String(Box<str>),
    IntVector(Box<[i32]>),
    StringVector(Box<[String]>),
    FloatArray(Box<[f32]>),
    TwoInt(i32, i32),
    File(Option<Box<File>>),
    Object(Box<[FieldIdx]>),
}

macro_rules! types {
    ($insert:expr, $([$e:ident, $($i:literal),+]), + $(,)*) => {
        $(
            $insert((types!($e), &[$($i,)+]));
        )+
    };
    (Float) => {FieldType::Float(0.0)};
    (Char) => {FieldType::Char('\0')};
    (IntVector) => {FieldType::IntVector(Box::new([]))};
    (StringVector) => {FieldType::StringVector(Box::new([]))};
    (FloatArray) => {FieldType::FloatArray(Box::new([]))};
    (TwoInt) => {FieldType::TwoInt(0, 0)};
    (TwoBool) => {FieldType::TwoBool(false, false)};
}

fn hardcoded_type(parents: &'_ [impl AsRef<str>], name: impl AsRef<str>) -> Option<FieldType> {
    use std::lazy::SyncLazy;
    type PathTypeList<'a> = Vec<(&'a [&'a str], FieldType)>;
    static TYPES_MAP: SyncLazy<HashMap<&str, PathTypeList>> = SyncLazy::new(|| {
        let mut map = HashMap::new();
        let mut insert = |(tip, trace): (FieldType, &'static [&'static str])| {
            map.entry(*trace.last().unwrap())
                .or_insert_with(Vec::new)
                .push((&trace[0..trace.len() - 1], tip));
        };

        #[rustfmt::skip]
        types!(insert,
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
            [IntVector, "dead_hero_entries"],
            [IntVector, "additional_mash_disabled_infestation_monster_class_ids"],
            [IntVector, "mash", "valid_additional_mash_entry_indexes"],
            [IntVector, "party", "heroes"],
            [IntVector, "skill_cooldown_keys"],
            [IntVector, "skill_cooldown_values"],
            [IntVector, "bufferedSpawningSlotsAvailable"],
            [IntVector, "curioGroups", "*", "curios"],
            [IntVector, "curioGroups", "*", "curio_table_entries"],
            [IntVector, "raid_finish_quirk_monster_class_ids"],
            [IntVector, "narration_audio_event_queue_tags"],
            [IntVector, "dispatched_events"],
            [IntVector, "backer_heroes", "*", "combat_skills"],
            [IntVector, "backer_heroes", "*", "camping_skills"],
            [IntVector, "backer_heroes", "*", "quirks"],

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

        map
    });
    if let Some(candidates) = TYPES_MAP.get(name.as_ref()) {
        candidates.iter().find_map(|(path, t)| {
            if parents.len() >= path.len()
                && path
                    .iter()
                    .rev()
                    .zip(parents.iter().rev())
                    .all(|(&tst, name_frag)| tst == "*" || tst == name_frag.as_ref())
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

impl FieldType {
    fn unwrap_object(&self) -> &[FieldIdx] {
        match self {
            FieldType::Object(ref v) => v,
            _ => panic!("attempted to unwrap non-object field type {:?}", self),
        }
    }

    fn unwrap_object_mut(&mut self) -> &mut [FieldIdx] {
        match self {
            FieldType::Object(ref mut v) => v,
            _ => panic!("attempted to unwrap non-object field type {:?}", self),
        }
    }
}
