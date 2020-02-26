use json_tools::{
    Buffer, BufferType, Lexer as JsonLexer, Token as JsonToken, TokenType as JsonTokenType,
};
use std::{
    borrow::Cow,
    iter::Peekable,
    ops::{Generator, GeneratorState},
    pin::Pin,
};

use crate::{err::JsonError, util::unescape};

#[derive(Copy, Clone, PartialEq, Eq, Debug)]
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
}

#[derive(Copy, Clone, Debug)]
pub struct Location {
    pub first: u64,
    pub end: u64,
}

#[derive(Clone, Debug)]
pub struct Token<'a> {
    pub kind: TokenType,
    pub dat: Cow<'a, str>,
    pub loc: Location,
}

macro_rules! span {
    ($e:expr) => {
        if let Buffer::Span(s) = $e {
            s
        } else {
            debug_assert!(false, "buffer not span");
            // Safety: json_tools lexer instanciated with BufferType::Span
            unsafe {
                core::hint::unreachable_unchecked();
            }
        }
    };
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
        let mut lex = JsonLexer::new(data.bytes(), BufferType::Span).peekable();
        if lex.peek().is_some() {
            ungen!(parse_value, data, lex)
        }
        while lex.peek().is_some() {
            expect_control(JsonTokenType::Comma, &mut lex)?;
            ungen!(parse_value, data, lex)
        }
        Ok(())
    })
}

fn eat<'b, 'a, T: Iterator<Item = JsonToken>>(
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
                format!("{:?}", exp),
                tok.loc.first,
                tok.loc.end,
            ));
        }
    }
    Err(JsonError::EOF)
}

fn lib_to_self(lib: JsonTokenType) -> Option<TokenType> {
    Some(match lib {
        JsonTokenType::CurlyOpen => TokenType::BeginObject,
        JsonTokenType::CurlyClose => TokenType::EndObject,
        JsonTokenType::BracketOpen => TokenType::BeginArray,
        JsonTokenType::BracketClose => TokenType::EndArray,
        JsonTokenType::Null => TokenType::Null,
        JsonTokenType::String => TokenType::String,
        JsonTokenType::Number => TokenType::Number,
        JsonTokenType::BooleanTrue => TokenType::BoolTrue,
        JsonTokenType::BooleanFalse => TokenType::BoolFalse,
        JsonTokenType::Colon | JsonTokenType::Comma | JsonTokenType::Invalid => return None,
    })
}

// E0626: This takes and returns ownership of the lexer because calling functions
// can't hold onto `lex` while yielding our items
fn parse_object<'b, 'a: 'b, T: Iterator<Item = JsonToken> + 'a>(
    data: &'a str,
    mut lex: Peekable<T>,
) -> Box<dyn Generator<Yield = Token<'a>, Return = Result<Peekable<T>, JsonError>> + Unpin + 'a> {
    Box::new(move || {
        yield eat(data, TokenType::BeginObject, &mut lex)?;

        loop {
            let tok = lex.peek().ok_or(JsonError::EOF)?;
            match tok.kind {
                JsonTokenType::CurlyClose => {
                    yield json_to_token(data, lex.next().unwrap())?;
                    break;
                }
                _ => {
                    let mut name = eat(data, TokenType::String, &mut lex)?;
                    name.kind = TokenType::FieldName;
                    yield name;
                    expect_control(JsonTokenType::Colon, &mut lex)?;
                    ungen!(parse_value, data, lex);
                    let tok2 = lex.next().ok_or(JsonError::EOF)?;
                    match tok2.kind {
                        JsonTokenType::CurlyClose => {
                            yield json_to_token(data, tok2)?;
                            break;
                        }
                        JsonTokenType::Comma => {
                            continue;
                        }
                        _ => {
                            let span = span!(&tok2.buf);
                            return Err(JsonError::Expected(
                                "expected , (comma) or } (closing brace)".to_owned(),
                                span.first,
                                span.end,
                            ));
                        }
                    }
                }
            }
        }

        Ok(lex)
    })
}

fn parse_array<'a, T: Iterator<Item = JsonToken> + 'a>(
    data: &'a str,
    mut lex: Peekable<T>,
) -> Box<dyn Generator<Yield = Token<'a>, Return = Result<Peekable<T>, JsonError>> + 'a> {
    Box::new(move || {
        yield eat(data, TokenType::BeginArray, &mut lex)?;

        loop {
            let tok = lex.peek().ok_or(JsonError::EOF)?;
            match tok.kind {
                JsonTokenType::BracketClose => {
                    yield json_to_token(data, lex.next().unwrap())?;
                    break;
                }
                _ => {
                    ungen!(parse_value, data, lex);
                    let tok2 = lex.next().ok_or(JsonError::EOF)?;
                    match tok2.kind {
                        JsonTokenType::BracketClose => {
                            yield json_to_token(data, tok2)?;
                            break;
                        }
                        JsonTokenType::Comma => {
                            continue;
                        }
                        _ => {
                            let span = span!(&tok2.buf);
                            return Err(JsonError::Expected(
                                "expected , (comma) or ] (closing bracket)".to_owned(),
                                span.first,
                                span.end,
                            ));
                        }
                    }
                }
            }
        }

        Ok(lex)
    })
}

fn parse_value<'a, T: Iterator<Item = JsonToken> + 'a>(
    data: &'a str,
    mut lex: Peekable<T>,
) -> Box<dyn Generator<Yield = Token<'a>, Return = Result<Peekable<T>, JsonError>> + 'a> {
    Box::new(move || {
        let tok = lex.peek().ok_or(JsonError::EOF)?;
        match tok.kind {
            JsonTokenType::CurlyOpen => ungen!(parse_object, data, lex),
            JsonTokenType::BracketOpen => ungen!(parse_array, data, lex),
            JsonTokenType::Number
            | JsonTokenType::BooleanTrue
            | JsonTokenType::BooleanFalse
            | JsonTokenType::String
            | JsonTokenType::Null => yield parse_single(data, &mut lex)?,
            _ => {
                let span = span!(&tok.buf);
                return Err(JsonError::ExpectedValue(span.first, span.end));
            }
        }
        Ok(lex)
    })
}

fn parse_single<'b, 'a: 'b, T: Iterator<Item = JsonToken>>(
    data: &'a str,
    lex: &'b mut Peekable<T>,
) -> Result<Token<'a>, JsonError> {
    let tok = lex.next().ok_or(JsonError::EOF)?;
    json_to_token(data, tok)
}

fn expect_control<T: Iterator<Item = JsonToken>>(
    exp: JsonTokenType,
    lex: &mut Peekable<T>,
) -> Result<(), JsonError> {
    let tok = lex.next().ok_or(JsonError::EOF)?;
    if tok.kind == exp {
        Ok(())
    } else {
        let span = span!(tok.buf);
        Err(JsonError::Expected(
            format!("{:?}", exp),
            span.first,
            span.end,
        ))
    }
}

fn json_to_token(data: &str, tok: JsonToken) -> Result<Token<'_>, JsonError> {
    let span = span!(tok.buf);

    let str_data = if tok.kind == JsonTokenType::String {
        let st = &data[span.first as usize + 1..span.end as usize - 1];
        unescape(&st).ok_or(JsonError::BareControl(span.first, span.end))?
    } else {
        Cow::from(&data[span.first as usize..span.end as usize])
    };

    let loc = Location {
        first: span.first,
        end: span.end,
    };
    match lib_to_self(tok.kind) {
        Some(t) => Ok(Token {
            dat: str_data,
            kind: t,
            loc,
        }),
        None => Err(JsonError::ExpectedValue(span.first, span.end)),
    }
}

pub(crate) trait ExpectExt<'a> {
    fn expect(&mut self, exp: TokenType, eat: bool) -> Result<Token<'a>, JsonError>;
}

impl<'a, T: Iterator<Item = Result<Token<'a>, JsonError>>> ExpectExt<'a>
    for std::iter::Peekable<T>
{
    fn expect(&mut self, exp: TokenType, eat: bool) -> Result<Token<'a>, JsonError> {
        let tok = if eat {
            self.next().ok_or(JsonError::EOF)??
        } else {
            self.peek().ok_or(JsonError::EOF)?.clone()?
        };
        if tok.kind != exp {
            return Err(JsonError::Expected(
                format!("{:?}", exp),
                tok.loc.first,
                tok.loc.end,
            ));
        }
        Ok(tok)
    }
}
