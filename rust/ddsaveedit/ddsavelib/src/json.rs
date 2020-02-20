use json_tools::{
    Buffer, BufferType, Lexer as JsonLexer, Token as JsonToken, TokenType as JsonTokenType,
};
use std::{
    borrow::Cow,
    iter::Peekable,
    ops::{Generator, GeneratorState},
    pin::Pin,
};

use super::util::unescape;

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

#[derive(Debug, Clone)]
pub enum JsonError {
    EOF,
    ExpectedValue(u64, u64),
    Expected(String, u64, u64),
    BareControl(u64, u64),
}

impl std::fmt::Display for JsonError {
    fn fmt(&self, fmt: &mut std::fmt::Formatter<'_>) -> std::result::Result<(), std::fmt::Error> {
        std::fmt::Debug::fmt(self, fmt)
    }
}

impl std::error::Error for JsonError {}

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
            unreachable!("buffer not span")
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

pub struct Parser<'a> {
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
        let res = self.gen.as_mut().resume(());
        match res {
            GeneratorState::Yielded(tok) => return Some(Ok(tok)),
            GeneratorState::Complete(err) => match err {
                Ok(_) => return None,
                Err(e) => {
                    return Some(Err(e));
                }
            },
        }
    }
}

pub fn parse<'a>(
    data: &'a str,
) -> Box<dyn Generator<Yield = Token<'a>, Return = Result<(), JsonError>> + 'a> {
    Box::new(move || {
        let mut lex = JsonLexer::new(data.bytes(), BufferType::Span).peekable();
        if let Some(_) = lex.peek() {
            ungen!(parse_value, data, lex)
        }
        while let Some(_) = lex.peek() {
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

/*
fn is_value(lib: &JsonTokenType) -> bool {
    match lib {
        JsonTokenType::Null
        | JsonTokenType::String
        | JsonTokenType::Number
        | JsonTokenType::BooleanTrue
        | JsonTokenType::BooleanFalse => true,
        _ => false,
    }
}*/

// E0626: This takes and returns ownership of the lexer as calling functions
// can't hold onto `lex` while yielding our items
fn parse_object<'b, 'a: 'b, T: Iterator<Item = JsonToken> + 'a>(
    data: &'a str,
    mut lex: Peekable<T>,
) -> Box<dyn Generator<Yield = Token<'a>, Return = Result<Peekable<T>, JsonError>> + Unpin + 'a> {
    Box::new(move || {
        #[derive(PartialEq, Eq)]
        enum Expecting {
            CommaOrClose,
            FieldOrClose,
            Field,
        }

        yield eat(data, TokenType::BeginObject, &mut lex)?;

        let mut state = Expecting::FieldOrClose;

        loop {
            let tok = lex.next().ok_or(JsonError::EOF)?;
            match &tok.kind {
                &JsonTokenType::CurlyClose if state != Expecting::Field => {
                    yield json_to_token(data, tok)?;
                    break;
                }
                &JsonTokenType::Comma if state == Expecting::CommaOrClose => {
                    state = Expecting::Field;
                }
                &JsonTokenType::String if state != Expecting::CommaOrClose => {
                    let mut rtok = json_to_token(data, tok)?;
                    rtok.kind = TokenType::FieldName;
                    yield rtok;
                    expect_control(JsonTokenType::Colon, &mut lex)?;
                    ungen!(parse_value, data, lex);
                    state = Expecting::CommaOrClose;
                }
                _ => {
                    let span = span!(&tok.buf);
                    return Err(JsonError::Expected(
                        match state {
                            Expecting::CommaOrClose => {
                                "expected , (comma) or } (closing brace)".to_owned()
                            }
                            Expecting::FieldOrClose => {
                                "expected field name or } (closing brace)".to_owned()
                            }
                            Expecting::Field => {
                                println!("got {:?}", tok);
                                "expected field name".to_owned()
                            }
                        },
                        span.first,
                        span.end,
                    ));
                }
            }
        }
        return Ok(lex);
    })
}

fn parse_array<'a, T: Iterator<Item = JsonToken> + 'a>(
    data: &'a str,
    mut lex: Peekable<T>,
) -> Box<dyn Generator<Yield = Token<'a>, Return = Result<Peekable<T>, JsonError>> + 'a> {
    Box::new(move || {
        #[derive(PartialEq, Eq)]
        enum Expecting {
            CommaOrClose,
            ValueOrClose,
            Value,
        }

        yield eat(data, TokenType::BeginArray, &mut lex)?;

        let mut state = Expecting::ValueOrClose;

        loop {
            let tok = lex.peek().ok_or(JsonError::EOF)?;
            match &tok.kind {
                &JsonTokenType::BracketClose if state != Expecting::Value => {
                    yield json_to_token(data, lex.next().unwrap())?;
                    break;
                }
                &JsonTokenType::Comma if state == Expecting::CommaOrClose => {
                    let _ = lex.next().unwrap();
                    state = Expecting::Value;
                }
                &_ if state != Expecting::CommaOrClose => {
                    ungen!(parse_value, data, lex);
                    state = Expecting::CommaOrClose;
                }
                &_ => {
                    let span = span!(&tok.buf);
                    return Err(JsonError::Expected(
                        match state {
                            Expecting::CommaOrClose => {
                                "expected , (comma) or ] (closing bracket)".to_owned()
                            }
                            Expecting::ValueOrClose => {
                                "expected value or } (closing bracket)".to_owned()
                            }
                            Expecting::Value => "expected value".to_owned(),
                        },
                        span.first,
                        span.end,
                    ));
                }
            }
        }

        return Ok(lex);
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

fn expect_control<'a, T: Iterator<Item = JsonToken>>(
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

pub fn json_to_token<'a>(data: &'a str, tok: JsonToken) -> Result<Token<'a>, JsonError> {
    let span = span!(tok.buf);

    let str_data = if tok.kind == JsonTokenType::String {
        let st = &data[span.first as usize + 1..span.end as usize - 1];
        let c = unescape(&st).ok_or(JsonError::BareControl(span.first, span.end))?;
        c
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

/*pub struct Parser<'a> {
    data: &'a str,
    lex: JsonLexer<std::str::Bytes<'a>>,
    peeked: Option<Token<'a>>,
}*/
/*
impl<'a> Parser<'a> {
    pub fn new(data: &'a str) -> Parser<'a> {
        Self {
            data,
            lex: JsonLexer::new(data.bytes(), BufferType::Span),
            peeked: None,
            ctx: vec![Context::Value],
        }
    }

    fn token_from(&self, jsontok: JsonToken) -> Token<'a> {
        let span = match jsontok.buf {
            Buffer::MultiByte(_) => unreachable!("requested span"),
            Buffer::Span(s) => s,
        };

    }

    pub fn next_token(&mut self) -> Result<Token<'a>, JsonError> {
        let jsontok = self.lex.next().ok_or(JsonError::EOF)?;
        match self.ctx.last().ok_or(JsonError::EOF)? {
            Context::Value => match jsontok.kind {
                JsonTokenType::CurlyOpen => self.ctx.push(Context::Object),
                JsonTokenType::Number
                | JsonTokenType::BooleanTrue
                | JsonTokenType::BooleanFalse
                | JsonTokenType::String
                | JsonTokenType::Null => {
                    self.ctx.pop().unwrap();
                }
            },
        }
        let tok = self.token_from(jsontok);
        Ok(tok)
    }

    pub fn peek(&mut self) -> Result<Token<'a>, JsonError> {
        match self.peeked {
            Some(tok) => Ok(tok),
            None => {
                let tok = self.next_token()?;
                self.peeked = Some(tok);
                Ok(tok)
            }
        }
    }

    pub fn next(&mut self) -> Result<Token<'a>, JsonError> {
        match self.peeked {
            Some(tok) => Ok(tok),
            None => self.next_token(),
        }
    }
}*/
