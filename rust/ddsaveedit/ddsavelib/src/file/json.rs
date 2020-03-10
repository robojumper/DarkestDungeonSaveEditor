use std::{
    borrow::Cow,
    iter::Peekable,
    ops::{Generator, GeneratorState},
    pin::Pin,
};

use crate::{
    err::JsonError,
    util::{is_whitespace, unescape},
};

#[derive(Copy, Clone, PartialEq, Eq)]
pub enum TokenType {
    BeginObject,
    EndObject,
    BeginArray,
    EndArray,
    FieldName,
    Number,
    BoolTrue,
    BoolFalse,
    String,
    Null,

    // Private variants
    Invalid,
    Comma,
    Colon,
}

impl std::fmt::Debug for TokenType {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> Result<(), std::fmt::Error> {
        self.as_ref().fmt(f)
    }
}

impl AsRef<str> for TokenType {
    fn as_ref(&self) -> &str {
        use TokenType::*;
        match self {
            BeginObject => "{",
            EndObject => "}",
            BeginArray => "[",
            EndArray => "]",
            FieldName => "<field name>",
            Number => "<number>",
            BoolTrue => "true",
            BoolFalse => "false",
            String => "<string>",
            Null => "null",
            Comma => ",",
            Colon => ":",
            Invalid => "<invalid>",
        }
    }
}

struct Lexer<'a> {
    src: &'a str,
    it: std::iter::Peekable<std::str::CharIndices<'a>>,
}

impl<'a> Lexer<'a> {
    fn new(src: &'a str) -> Self {
        Self {
            src,
            it: src.char_indices().peekable(),
        }
    }

    fn cur_pos(&mut self) -> usize {
        self.it
            .peek()
            .map(|i| i.0)
            .unwrap_or_else(|| self.src.len())
    }
}

impl<'a> Iterator for Lexer<'a> {
    type Item = LexerToken;
    fn next(&mut self) -> Option<Self::Item> {
        let mut tup;
        // Skip whitespace
        while {
            tup = self.it.next()?;
            is_whitespace(tup.1)
        } {}
        let (kind, first, end) = match tup.1 {
            '{' => (TokenType::BeginObject, tup.0, self.cur_pos()),
            '}' => (TokenType::EndObject, tup.0, self.cur_pos()),
            '[' => (TokenType::BeginArray, tup.0, self.cur_pos()),
            ']' => (TokenType::EndArray, tup.0, self.cur_pos()),
            ':' => (TokenType::Colon, tup.0, self.cur_pos()),
            ',' => (TokenType::Comma, tup.0, self.cur_pos()),
            't' => {
                // Attempt `true`
                if self.it.next()?.1 == 'r' && self.it.next()?.1 == 'u' && self.it.next()?.1 == 'e'
                {
                    (TokenType::BoolTrue, tup.0, self.cur_pos())
                } else {
                    (TokenType::Invalid, tup.0, self.cur_pos())
                }
            }
            'f' => {
                // Attempt `false`
                if self.it.next()?.1 == 'a'
                    && self.it.next()?.1 == 'l'
                    && self.it.next()?.1 == 's'
                    && self.it.next()?.1 == 'e'
                {
                    (TokenType::BoolFalse, tup.0, self.cur_pos())
                } else {
                    (TokenType::Invalid, tup.0, self.cur_pos())
                }
            }
            'n' => {
                // Attempt `null`
                if self.it.next()?.1 == 'u' && self.it.next()?.1 == 'l' && self.it.next()?.1 == 'l'
                {
                    (TokenType::Null, tup.0, self.cur_pos())
                } else {
                    (TokenType::Invalid, tup.0, self.cur_pos())
                }
            }
            '"' => {
                // Attempt string
                let mut prev = None;
                while {
                    let nxt = self.it.next()?;
                    if prev.is_some() && prev.unwrap() == '\\' && nxt.1 == '\"' {
                        prev = Some(nxt.1);
                        true
                    } else {
                        prev = Some(nxt.1);
                        !matches!(nxt.1, '"')
                    }
                } {}
                (TokenType::String, tup.0, self.cur_pos())
            }
            '0'..='9' | '-' | '+' | '.' | 'E' | 'e' => {
                // Attempt number
                while matches!(self.it.peek()?.1, '0'..='9' | '-' | '+' | '.' | 'E' | 'e') {
                    self.it.next();
                }
                (TokenType::Number, tup.0, self.cur_pos())
            }
            _ => {
                // Invalid
                (TokenType::Invalid, tup.0, self.cur_pos())
            }
        };

        Some(LexerToken {
            kind,
            span: Span { first, end },
        })
    }
}

#[derive(Copy, Clone, Debug)]
pub struct Span {
    pub first: usize,
    pub end: usize,
}

struct LexerToken {
    pub kind: TokenType,
    pub span: Span,
}

#[derive(Clone, Debug)]
pub struct Token<'a> {
    pub kind: TokenType,
    pub dat: Cow<'a, str>,
    pub span: Span,
}

macro_rules! ungen {
    ($f:ident, $data:expr, $l:ident) => {{
        let mut gen: Pin<Box<_>> = $f($data, $l).into();
        loop {
            let res = gen.as_mut().resume(());
            match res {
                GeneratorState::Yielded(tok) => yield tok,
                GeneratorState::Complete(r) => match r {
                    Ok(l) => {
                        $l = l;
                        break;
                    }
                    Err(er) => return Err(er),
                },
            }
        }
    }};
}

pub(crate) struct Parser<'a> {
    gen: Pin<Box<dyn Generator<Yield = Token<'a>, Return = Result<(), JsonError>> + 'a>>,
}

impl<'a> Parser<'a> {
    pub fn new(data: &'a str) -> Self {
        Self {
            gen: parse(data).into(),
        }
    }
}

impl<'a> Iterator for Parser<'a> {
    type Item = Result<Token<'a>, JsonError>;
    fn next(&mut self) -> Option<Self::Item> {
        match self.gen.as_mut().resume(()) {
            GeneratorState::Yielded(tok) => Some(Ok(tok)),
            GeneratorState::Complete(err) => match err {
                Ok(_) => None,
                Err(e) => Some(Err(e)),
            },
        }
    }
}

pub(crate) fn parse<'a>(
    data: &'a str,
) -> Box<dyn Generator<Yield = Token<'a>, Return = Result<(), JsonError>> + 'a> {
    Box::new(move || {
        let mut lex = Lexer::new(data).peekable();
        if lex.peek().is_some() {
            ungen!(parse_value, data, lex)
        }
        while lex.peek().is_some() {
            expect_control(TokenType::Comma, &mut lex)?;
            ungen!(parse_value, data, lex)
        }
        Ok(())
    })
}

fn eat<'b, 'a, T: Iterator<Item = LexerToken>>(
    data: &'a str,
    exp: TokenType,
    lex: &'b mut Peekable<T>,
) -> Result<Token<'a>, JsonError> {
    if lex.peek().is_some() {
        let tok = parse_single(data, lex)?;
        if tok.kind == exp {
            return Ok(tok);
        } else {
            return Err(JsonError::Expected(
                exp.as_ref().to_owned(),
                tok.span.first,
                tok.span.end,
            ));
        }
    }
    Err(JsonError::EOF)
}

// E0626: This takes and returns ownership of the lexer because calling functions
// can't hold onto `lex` while yielding our items
fn parse_object<'a, T: Iterator<Item = LexerToken> + 'a>(
    data: &'a str,
    mut lex: Peekable<T>,
) -> Box<dyn Generator<Yield = Token<'a>, Return = Result<Peekable<T>, JsonError>> + 'a> {
    Box::new(move || {
        yield eat(data, TokenType::BeginObject, &mut lex)?;

        loop {
            let tok = lex.peek().ok_or(JsonError::EOF)?;
            match tok.kind {
                TokenType::EndObject => {
                    yield json_to_token(data, lex.next().unwrap())?;
                    break;
                }
                _ => {
                    let mut name = eat(data, TokenType::String, &mut lex)?;
                    name.kind = TokenType::FieldName;
                    yield name;
                    expect_control(TokenType::Colon, &mut lex)?;
                    ungen!(parse_value, data, lex);
                    let tok2 = lex.next().ok_or(JsonError::EOF)?;
                    match tok2.kind {
                        TokenType::EndObject => {
                            yield json_to_token(data, tok2)?;
                            break;
                        }
                        TokenType::Comma => {
                            continue;
                        }
                        _ => {
                            return Err(JsonError::Expected(
                                "expected , (comma) or } (closing brace)".to_owned(),
                                tok2.span.first,
                                tok2.span.end,
                            ));
                        }
                    }
                }
            }
        }

        Ok(lex)
    })
}

fn parse_array<'a, T: Iterator<Item = LexerToken> + 'a>(
    data: &'a str,
    mut lex: Peekable<T>,
) -> Box<dyn Generator<Yield = Token<'a>, Return = Result<Peekable<T>, JsonError>> + 'a> {
    Box::new(move || {
        yield eat(data, TokenType::BeginArray, &mut lex)?;

        loop {
            let tok = lex.peek().ok_or(JsonError::EOF)?;
            match tok.kind {
                TokenType::EndArray => {
                    yield json_to_token(data, lex.next().unwrap())?;
                    break;
                }
                _ => {
                    ungen!(parse_value, data, lex);
                    let tok2 = lex.next().ok_or(JsonError::EOF)?;
                    match tok2.kind {
                        TokenType::EndArray => {
                            yield json_to_token(data, tok2)?;
                            break;
                        }
                        TokenType::Comma => {
                            continue;
                        }
                        _ => {
                            return Err(JsonError::Expected(
                                "expected , (comma) or ] (closing bracket)".to_owned(),
                                tok2.span.first,
                                tok2.span.end,
                            ));
                        }
                    }
                }
            }
        }

        Ok(lex)
    })
}

fn parse_value<'a, T: Iterator<Item = LexerToken> + 'a>(
    data: &'a str,
    mut lex: Peekable<T>,
) -> Box<dyn Generator<Yield = Token<'a>, Return = Result<Peekable<T>, JsonError>> + 'a> {
    Box::new(move || {
        let tok = lex.peek().ok_or(JsonError::EOF)?;
        match tok.kind {
            TokenType::BeginObject => ungen!(parse_object, data, lex),
            TokenType::BeginArray => ungen!(parse_array, data, lex),
            TokenType::Number
            | TokenType::BoolTrue
            | TokenType::BoolFalse
            | TokenType::String
            | TokenType::Null => yield parse_single(data, &mut lex)?,
            _ => {
                return Err(JsonError::ExpectedValue(tok.span.first, tok.span.end));
            }
        }
        Ok(lex)
    })
}

fn parse_single<'b, 'a: 'b, T: Iterator<Item = LexerToken>>(
    data: &'a str,
    lex: &'b mut T,
) -> Result<Token<'a>, JsonError> {
    let tok = lex.next().ok_or(JsonError::EOF)?;
    json_to_token(data, tok)
}

fn expect_control<T: Iterator<Item = LexerToken>>(
    exp: TokenType,
    lex: &mut T,
) -> Result<(), JsonError> {
    let tok = lex.next().ok_or(JsonError::EOF)?;
    if tok.kind == exp {
        Ok(())
    } else {
        Err(JsonError::Expected(
            exp.as_ref().to_owned(),
            tok.span.first,
            tok.span.end,
        ))
    }
}

fn json_to_token(data: &str, tok: LexerToken) -> Result<Token<'_>, JsonError> {
    let span = tok.span;
    let str_data = Cow::from(&data[span.first as usize..span.end as usize]);

    let str_data = match tok.kind {
        TokenType::String => {
            let st = &data[span.first as usize + 1..span.end as usize - 1];
            unescape(&st).ok_or(JsonError::BareControl(span.first, span.end))?
        }
        TokenType::Number => {
            if str_data.parse::<i32>().is_err() && str_data.parse::<f32>().is_err() {
                return Err(JsonError::BadNumber(span.first, span.end));
            }
            str_data
        }
        _ => str_data,
    };

    Ok(Token {
        kind: tok.kind,
        dat: str_data,
        span,
    })
}

pub(crate) trait ExpectExt<'a> {
    fn expect(&mut self, exp: TokenType) -> Result<Token<'a>, JsonError>;
}

impl<'a, T: Iterator<Item = Result<Token<'a>, JsonError>>> ExpectExt<'a> for T {
    fn expect(&mut self, exp: TokenType) -> Result<Token<'a>, JsonError> {
        let tok = self.next().ok_or(JsonError::EOF)??;
        if tok.kind != exp {
            return Err(JsonError::Expected(
                exp.as_ref().to_owned(),
                tok.span.first,
                tok.span.end,
            ));
        }
        Ok(tok)
    }
}
