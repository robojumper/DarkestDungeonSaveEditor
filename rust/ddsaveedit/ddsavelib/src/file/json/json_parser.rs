//! A simple JSON lexer and parser. Accepts all valid JSON, and accepts some invalid JSON.
//! In particular, floating point numbers simply use the Rust core f32 parser -- see [`<f32 as FromStr>::from_str`](https://doc.rust-lang.org/core/str/trait.FromStr.html#impl-FromStr-14).

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
    last_span: Span,
    it: std::iter::Peekable<std::str::CharIndices<'a>>,
}

impl<'a> Lexer<'a> {
    /// Construct a simple JSON lexer from a string slice. The Lexer will
    /// sometimes yield invalid number and string tokens that must be
    /// checked.
    fn new(src: &'a str) -> Self {
        Self {
            src,
            last_span: Span { first: 0, end: 0 },
            it: src.char_indices().peekable(),
        }
    }

    /// Return the span of the last yielded token. The tokens yielded by the
    /// lexer do not include spans as to keep the memory size of a token low.
    fn last_span(&self) -> &Span {
        &self.last_span
    }

    /// Return the byte position of the next character that will be looked at.
    /// Whitespace might be skipped.
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

        self.last_span = Span {
            first: tup.0,
            end: self.cur_pos(),
        };

        Some(LexerToken { kind })
    }
}

// CharIndices is Fused, we are Fused as well.
impl<'a> FusedIterator for Lexer<'a> {}

/// The span of a token, as described by the byte index of the first character
/// (inclusive), and the end of the token (exclusive).
#[derive(Copy, Clone, Debug)]
pub struct Span {
    pub first: usize,
    pub end: usize,
}

struct LexerToken {
    pub kind: TokenType,
}

/// A JSON Token. If `kind` is of [`TokenType::String`], `dat` will be escaped already.
#[derive(Clone, Debug)]
pub struct Token<'a> {
    pub kind: TokenType,
    pub dat: Cow<'a, str>,
}

/// Represents the errors that can occur upon parsing the file as JSON.
#[derive(Debug, Clone)]
pub enum JsonError {
    /// A token was expected, but the file ended.
    Eof,
    /// A value was expected, but a non-value token was found.
    ExpectedValue(usize, usize),
    /// A specific token was expected, but another was found.
    Expected(String, usize, usize),
    /// The string contained a raw control character such as `\n` instead of
    /// `\\n`.
    BareControl(usize, usize),
    /// Parsing of the number as an [`i32`] or [`f32`] failed.
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
            JsonError::Eof => FromJsonError::UnexpEof,
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
    peeked: Option<<Self as Iterator>::Item>,
    block_stack: Vec<ParserBlock>,
}

impl<'a> Parser<'a> {
    pub fn new(src: &'a str) -> Self {
        Self {
            lexer: Lexer::new(src),
            data: src,
            peeked: None,
            block_stack: vec![ParserBlock::Value { need_colon: false }],
        }
    }

    pub fn span(&self) -> &Span {
        self.lexer.last_span()
    }

    pub fn peek(&mut self) -> Option<&<Self as Iterator>::Item> {
        if self.peeked.is_none() {
            self.peeked = if self.block_stack.is_empty() {
                None
            } else {
                Some(self.next_inner())
            }
        }
        self.peeked.as_ref()
    }

    pub fn expect(&mut self, exp: TokenType) -> Result<Token<'a>, JsonError> {
        let tok = self.next().ok_or(JsonError::Eof)??;
        if tok.kind != exp {
            return Err(JsonError::Expected(
                exp.as_ref().to_owned(),
                self.span().first,
                self.span().end,
            ));
        }
        Ok(tok)
    }

    /// Return `Ok(_)` if the next token exists, `Err(_)` if the next token is
    /// erroneous, and `Err(FromJsonError::Eof)` if there is no text token.
    pub fn exp_next(&mut self) -> Result<Token<'a>, JsonError> {
        self.next().ok_or(JsonError::Eof)?
    }

    fn parse_value(&mut self, next_tok: LexerToken) -> <Self as Iterator>::Item {
        match next_tok.kind {
            TokenType::BeginObject => {
                self.block_stack
                    .push(ParserBlock::Object { need_comma: false });
                self.json_to_token(next_tok)
            }
            TokenType::BeginArray => {
                self.block_stack
                    .push(ParserBlock::Array { need_comma: false });
                self.json_to_token(next_tok)
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
                self.json_to_token(next_tok)
            }
            _ => Err(JsonError::ExpectedValue(
                self.lexer.last_span().first,
                self.lexer.last_span().end,
            )),
        }
    }

    fn next_inner(&mut self) -> <Self as Iterator>::Item {
        let block = self.block_stack.last_mut().unwrap();
        match block {
            ParserBlock::Value { need_colon } => {
                let mut next_tok = self.lexer.next().ok_or(JsonError::Eof)?;
                if *need_colon {
                    if next_tok.kind != TokenType::Colon {
                        return self.err_expected(TokenType::Colon);
                    }
                    next_tok = self.lexer.next().ok_or(JsonError::Eof)?;
                }
                *need_colon = false;
                self.parse_value(next_tok)
            }
            ParserBlock::Object { need_comma } => {
                let mut next_tok = self.lexer.next().ok_or(JsonError::Eof)?;
                match next_tok.kind {
                    TokenType::EndObject => {
                        self.block_stack.pop();
                        assert!(matches!(
                            self.block_stack.pop(),
                            Some(ParserBlock::Value { .. })
                        )); // Terminate value
                        self.json_to_token(next_tok)
                    }
                    _ => {
                        if *need_comma {
                            if next_tok.kind != TokenType::Comma {
                                return self.err_expected(TokenType::Comma);
                            }
                            next_tok = self.lexer.next().ok_or(JsonError::Eof)?;
                        }
                        *need_comma = true;
                        match next_tok.kind {
                            TokenType::String => {
                                self.block_stack
                                    .push(ParserBlock::Value { need_colon: true });
                                self.json_to_token(next_tok).map(|mut t| {
                                    t.kind = TokenType::FieldName;
                                    t
                                })
                            }
                            _ => self.err_expected(TokenType::FieldName),
                        }
                    }
                }
            }
            ParserBlock::Array { need_comma } => {
                let mut next_tok = self.lexer.next().ok_or(JsonError::Eof)?;
                match next_tok.kind {
                    TokenType::EndArray => {
                        self.block_stack.pop(); // Leave the array
                        assert!(matches!(
                            self.block_stack.pop(),
                            Some(ParserBlock::Value { .. })
                        )); // Terminate value
                        self.json_to_token(next_tok)
                    }
                    _ => {
                        if *need_comma {
                            if next_tok.kind != TokenType::Comma {
                                return self.err_expected(TokenType::Comma);
                            }
                            next_tok = self.lexer.next().ok_or(JsonError::Eof)?;
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

    fn json_to_token(&self, tok: LexerToken) -> Result<Token<'a>, JsonError> {
        let span = self.lexer.last_span();
        let str_data = Cow::from(&self.data[span.first as usize..span.end as usize]);

        let str_data = match tok.kind {
            TokenType::String => {
                let st = &self.data[span.first as usize + 1..span.end as usize - 1];
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
        })
    }

    fn err_expected(&self, exp: TokenType) -> Result<Token<'a>, JsonError> {
        let span = self.lexer.last_span();
        return Err(JsonError::Expected(
            exp.as_ref().to_owned(),
            span.first,
            span.end,
        ));
    }
}

impl<'a> Iterator for Parser<'a> {
    type Item = Result<Token<'a>, JsonError>;
    fn next(&mut self) -> Option<Self::Item> {
        if let tok @ Some(_) = std::mem::replace(&mut self.peeked, None) {
            return tok;
        }
        if self.block_stack.is_empty() {
            None
        } else {
            Some(self.next_inner())
        }
    }
}
