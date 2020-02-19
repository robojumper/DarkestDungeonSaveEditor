use std::borrow::Cow;

pub fn name_hash(s: &'_ str) -> i32 {
    s.bytes()
        .fold(0i32, |acc, c| acc.wrapping_mul(53).wrapping_add(c as i32))
}

pub fn escape(arg: &str) -> Cow<str> {
    if arg
        .chars()
        .find(|&c| {
            c == '\x08'
                || c == '\x0C'
                || c == '\n'
                || c == '\r'
                || c == '\t'
                || c == '"'
                || c == '\\'
        })
        .is_some()
    {
        let mut s = String::new();
        for c in arg.chars() {
            match c {
                '\x08' => s.push_str("\\b"),
                '\x0C' => s.push_str("\\f"),
                '\n' => s.push_str("\\n"),
                '\r' => s.push_str("\\r"),
                '\t' => s.push_str("\\t"),
                '"' => s.push_str("\\\""),
                '\\' => s.push_str("\\\\"),
                _ => s.push(c),
            }
        }
        return Cow::Owned(s);
    }
    return Cow::Borrowed(arg);
}

pub fn unescape(arg: &str) -> Option<Cow<str>> {
    if arg.find('\\').is_some() {
        let mut s = String::new();
        let mut it = arg.chars();
        while let Some(c) = it.next() {
            match c {
                '\x08' | '\x0C' | '\n' | '\r' | '\t' | '\"' => return None,
                '\\' => {}
                c => {
                    s.push(c);
                    continue;
                }
            }

            s.push(match it.next() {
                Some('b') => '\x08',
                Some('f') => '\x0C',
                Some('n') => '\n',
                Some('r') => '\r',
                Some('t') => '\t',
                Some('"') => '\"',
                _ => return None,
            });
        }
        return Some(Cow::Owned(s));
    }
    Some(Cow::Borrowed(arg))
}
