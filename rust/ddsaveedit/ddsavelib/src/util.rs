pub fn name_hash(s: &'_ str) -> i32 {
    s.bytes()
        .fold(0i32, |acc, c| acc.wrapping_mul(53).wrapping_add(c as i32))
}
