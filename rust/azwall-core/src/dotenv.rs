//! A small `.env` reader: `KEY=value` lines, `#` comments, optional `export ` prefix, single or
//! double quotes. Kept in-house so the parsing rules are explicit and tested.

use std::collections::BTreeMap;
use std::path::Path;

use crate::{CoreError, Result};

pub type EnvMap = BTreeMap<String, String>;

pub fn parse(text: &str) -> Result<EnvMap> {
    let mut map = EnvMap::new();
    for (index, raw) in text.lines().enumerate() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let line = line.strip_prefix("export ").unwrap_or(line);
        let err = |reason: &str| CoreError::Dotenv { line: index + 1, reason: reason.to_string() };
        let (key, value) = line.split_once('=').ok_or_else(|| err("expected KEY=value"))?;
        let key = key.trim();
        if key.is_empty() || !key.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
            return Err(err("keys use only A-Z, 0-9 and '_'"));
        }
        map.insert(key.to_string(), parse_value(value.trim()).map_err(err)?);
    }
    Ok(map)
}

fn parse_value(value: &str) -> std::result::Result<String, &'static str> {
    for quote in ['"', '\''] {
        if let Some(rest) = value.strip_prefix(quote) {
            let end = rest.find(quote).ok_or("unterminated quote")?;
            let after = rest[end + 1..].trim();
            if !after.is_empty() && !after.starts_with('#') {
                return Err("unexpected text after the closing quote");
            }
            return Ok(rest[..end].to_string());
        }
    }
    // Unquoted: an inline comment starts at " #".
    let value = value.split(" #").next().unwrap_or_default();
    Ok(value.trim().to_string())
}

/// Reads `path` if it exists (a missing `.env` is not an error), then lets `AZWALL_*` process
/// environment variables override it, as CI sets them.
pub fn load(path: &Path) -> Result<EnvMap> {
    let mut map = if path.exists() { parse(&std::fs::read_to_string(path)?)? } else { EnvMap::new() };
    map.extend(std::env::vars().filter(|(k, _)| k.starts_with("AZWALL_")));
    Ok(map)
}

/// The value of `key`, ignoring empty assignments (`KEY=` means "use the default").
pub fn non_empty<'a>(env: &'a EnvMap, key: &str) -> Option<&'a str> {
    env.get(key).map(String::as_str).filter(|v| !v.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_keys_comments_and_quotes() {
        let env = parse(
            "# comment\n\nA=1\nexport B = two \nC=\"quoted # not a comment\"\nD='single'\nE=x # trailing\nF=\n",
        )
        .unwrap();
        assert_eq!(env["A"], "1");
        assert_eq!(env["B"], "two");
        assert_eq!(env["C"], "quoted # not a comment");
        assert_eq!(env["D"], "single");
        assert_eq!(env["E"], "x");
        assert_eq!(env["F"], "");
    }

    #[test]
    fn keeps_urls_with_query_strings() {
        let env = parse("AZWALL_RPC_X=https://lb.example/aztec?key=abc=def\n").unwrap();
        assert_eq!(env["AZWALL_RPC_X"], "https://lb.example/aztec?key=abc=def");
    }

    #[test]
    fn reports_the_failing_line() {
        let err = parse("A=1\nnot a pair\n").unwrap_err();
        assert!(matches!(err, CoreError::Dotenv { line: 2, .. }));
        assert!(parse("BAD KEY=1").is_err());
        assert!(parse("A=\"open").is_err());
        assert!(parse("A=\"x\" y").is_err());
    }

    #[test]
    fn empty_values_count_as_unset() {
        let env = parse("A=\nB=b").unwrap();
        assert_eq!(non_empty(&env, "A"), None);
        assert_eq!(non_empty(&env, "B"), Some("b"));
        assert_eq!(non_empty(&env, "C"), None);
    }

    #[test]
    fn a_missing_file_is_empty() {
        let env = load(Path::new("/definitely/not/here/.env")).unwrap();
        assert!(env.keys().all(|k| k.starts_with("AZWALL_")));
    }
}
