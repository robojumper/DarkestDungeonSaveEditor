use std::{borrow::Cow, iter::FusedIterator};

use crate::{
    err::FromJsonError,
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

macro_rules! repeatedly_matches {
    ($e:expr, $($p:pat),+ $(,)*) => {
        ($(matches!($e, Some((_, $p))) && )+ true)
    };
}

impl<'a> Iterator for Lexer<'a> {
    type Item = LexerToken;
    fn next(&mut self) -> Option<Self::Item> {
        // Notes on matches!: on EOF, the match will fail and we'll likely bail out
        // with a `TokenType::Invalid`. Because `CharIndices` is fused, subsequent calls will simply
        // simply return `None` here.
        let mut tup;
        // Skip whitespace
        while {
            tup = self.it.next()?;
            is_whitespace(tup.1)
        } {}
        let kind = match tup.1 {
            '{' => TokenType::BeginObject,
            '}' => TokenType::EndObject,
            '[' => TokenType::BeginArray,
            ']' => TokenType::EndArray,
            ':' => TokenType::Colon,
            ',' => TokenType::Comma,
            't' => {
                // Attempt `true`
                if repeatedly_matches!(self.it.next(), 'r', 'u', 'e') {
                    TokenType::BoolTrue
                } else {
                    TokenType::Invalid
                }
            }
            'f' => {
                // Attempt `false`
                if repeatedly_matches!(self.it.next(), 'a', 'l', 's', 'e') {
                    TokenType::BoolFalse
                } else {
                    TokenType::Invalid
                }
            }
            'n' => {
                // Attempt `null`
                if repeatedly_matches!(self.it.next(), 'u', 'l', 'l') {
                    TokenType::Null
                } else {
                    TokenType::Invalid
                }
            }
            '"' => {
                // Attempt string
                let mut esc = false;
                loop {
                    let nxt = self.it.next()?;
                    match nxt.1 {
                        '\\' => esc = !esc,
                        '\"' if !esc => {
                            break;
                        }
                        _ => {
                            esc = false;
                        }
                    }
                }
                TokenType::String
            }
            '0'..='9' | '-' | '+' | '.' | 'E' | 'e' => {
                // Attempt number
                while matches!(
                    self.it.peek(),
                    Some((_, '0'..='9' | '-' | '+' | '.' | 'E' | 'e'))
                ) {
                    self.it.next();
                }
                TokenType::Number
            }
            _ => {
                // Invalid
                TokenType::Invalid
            }
        };

        Some(LexerToken {
            kind,
            span: Span {
                first: tup.0,
                end: self.cur_pos(),
            },
        })
    }
}

// CharIndices is Fused, we are Fused as well.
impl<'a> FusedIterator for Lexer<'a> {}

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

#[derive(Debug, Clone)]
pub enum JsonError {
    EOF,
    ExpectedValue(usize, usize),
    Expected(String, usize, usize),
    BareControl(usize, usize),
    BadNumber(usize, usize),
}

impl std::fmt::Display for JsonError {
    fn fmt(&self, fmt: &mut std::fmt::Formatter<'_>) -> std::result::Result<(), std::fmt::Error> {
        std::fmt::Debug::fmt(self, fmt)
    }
}

impl std::error::Error for JsonError {}

impl From<JsonError> for FromJsonError {
    fn from(err: JsonError) -> Self {
        <&JsonError as Into<FromJsonError>>::into(&err)
    }
}

impl<'a> From<&'a JsonError> for FromJsonError {
    fn from(err: &'a JsonError) -> Self {
        match err {
            JsonError::EOF => FromJsonError::UnexpEOF,
            JsonError::ExpectedValue(b, c) => FromJsonError::JsonErr(*b, *c),
            JsonError::BareControl(b, c) => {
                FromJsonError::LiteralFormat("bare control character".to_owned(), *b, *c)
            }
            JsonError::BadNumber(b, c) => {
                FromJsonError::LiteralFormat("bad number format".to_owned(), *b, *c)
            }
            JsonError::Expected(a, b, c) => FromJsonError::Expected(a.clone(), *b, *c),
        }
    }
}

enum ParserBlock {
    Value { need_colon: bool },
    Object { need_comma: bool },
    Array { need_comma: bool },
}

pub struct Parser<'a> {
    lexer: Lexer<'a>,
    data: &'a str,
    block_stack: Vec<ParserBlock>,
}

impl<'a> Parser<'a> {
    pub fn new(src: &'a str) -> Self {
        Self {
            lexer: Lexer::new(src),
            data: src,
            block_stack: vec![ParserBlock::Value { need_colon: false }],
        }
    }

    fn parse_value(&mut self, next_tok: LexerToken) -> <Self as Iterator>::Item {
        match next_tok.kind {
            TokenType::BeginObject => {
                self.block_stack
                    .push(ParserBlock::Object { need_comma: false });
                json_to_token(self.data, next_tok)
            }
            TokenType::BeginArray => {
                self.block_stack
                    .push(ParserBlock::Array { need_comma: false });
                json_to_token(self.data, next_tok)
            }
            TokenType::Number
            | TokenType::BoolTrue
            | TokenType::BoolFalse
            | TokenType::String
            | TokenType::Null => {
                assert!(matches!(
                    self.block_stack.pop(),
                    Some(ParserBlock::Value { .. })
                )); // Leave value
                json_to_token(self.data, next_tok)
            }
            _ => Err(JsonError::ExpectedValue(
                next_tok.span.first,
                next_tok.span.end,
            )),
        }
    }

    fn next_inner(&mut self) -> <Self as Iterator>::Item {
        let block = self.block_stack.last_mut().unwrap();
        match block {
            ParserBlock::Value { need_colon } => {
                let mut next_tok = self.lexer.next().ok_or(JsonError::EOF)?;
                if *need_colon {
                    if next_tok.kind != TokenType::Colon {
                        return Err(JsonError::Expected(
                            TokenType::Colon.as_ref().to_owned(),
                            next_tok.span.first,
                            next_tok.span.end,
                        ));
                    }
                    next_tok = self.lexer.next().ok_or(JsonError::EOF)?;
                }
                *need_colon = false;
                self.parse_value(next_tok)
            }
            ParserBlock::Object { need_comma } => {
                let mut next_tok = self.lexer.next().ok_or(JsonError::EOF)?;
                match next_tok.kind {
                    TokenType::EndObject => {
                        self.block_stack.pop();
                        assert!(matches!(
                            self.block_stack.pop(),
                            Some(ParserBlock::Value { .. })
                        )); // Terminate value
                        json_to_token(self.data, next_tok)
                    }
                    _ => {
                        if *need_comma {
                            if next_tok.kind != TokenType::Comma {
                                return Err(JsonError::Expected(
                                    TokenType::Comma.as_ref().to_owned(),
                                    next_tok.span.first,
                                    next_tok.span.end,
                                ));
                            }
                            next_tok = self.lexer.next().ok_or(JsonError::EOF)?;
                        }
                        *need_comma = true;
                        match next_tok.kind {
                            TokenType::String => {
                                self.block_stack
                                    .push(ParserBlock::Value { need_colon: true });
                                json_to_token(self.data, next_tok).and_then(|mut t| {
                                    t.kind = TokenType::FieldName;
                                    Ok(t)
                                })
                            }
                            _ => {
                                Err(JsonError::Expected(
                                    TokenType::FieldName.as_ref().to_owned(),
                                    next_tok.span.first,
                                    next_tok.span.end,
                                ))
                            }
                        }
                    }
                }
            }
            ParserBlock::Array { need_comma } => {
                let mut next_tok = self.lexer.next().ok_or(JsonError::EOF)?;
                match next_tok.kind {
                    TokenType::EndArray => {
                        self.block_stack.pop(); // Leave the array
                        assert!(matches!(
                            self.block_stack.pop(),
                            Some(ParserBlock::Value { .. })
                        )); // Terminate value
                        json_to_token(self.data, next_tok)
                    }
                    _ => {
                        if *need_comma {
                            if next_tok.kind != TokenType::Comma {
                                return Err(JsonError::Expected(
                                    TokenType::Comma.as_ref().to_owned(),
                                    next_tok.span.first,
                                    next_tok.span.end,
                                ));
                            }
                            next_tok = self.lexer.next().ok_or(JsonError::EOF)?;
                        }
                        *need_comma = true;
                        self.block_stack
                            .push(ParserBlock::Value { need_colon: false });
                        self.parse_value(next_tok)
                    }
                }
            }
        }
    }
}

impl<'a> Iterator for Parser<'a> {
    type Item = Result<Token<'a>, JsonError>;
    fn next(&mut self) -> Option<Self::Item> {
        match self.block_stack.is_empty() {
            true => None,
            false => Some(self.next_inner()),
        }
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
