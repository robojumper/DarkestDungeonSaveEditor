use std::borrow::Cow;

/// Calculate the hash of a given string according to the DD hashing algorithm.
pub fn name_hash(s: &'_ str) -> i32 {
    s.bytes().fold(0i32, |acc, c| {
        acc.wrapping_mul(53).wrapping_add(i32::from(c))
    })
}

pub fn escape(arg: &str) -> Cow<str> {
    if arg
        .chars()
        .any(|c| matches!(c, '\x08' | '\x0C' | '\n' | '\r' | '\t' | '\\' | '"'))
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
        Cow::Owned(s)
    } else {
        Cow::Borrowed(arg)
    }
}

pub fn unescape(arg: &str) -> Option<Cow<str>> {
    // Bare control characters are disallowed
    if arg
        .chars()
        .any(|c| matches!(c, '\x08' | '\x0C' | '\n' | '\r' | '\t'))
    {
        return None;
    }
    if arg.find('\\').is_some() {
        let mut s = String::new();
        let mut it = arg.chars();
        while let Some(c) = it.next() {
            match c {
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

pub fn is_whitespace(i: char) -> bool {
    return matches!(i, '\r' | '\n' | '\t' | ' ');
}
