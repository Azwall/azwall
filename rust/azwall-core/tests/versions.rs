//! The app's version is written in four places (the workspace, both apps, the dApp gateway): they must agree, and
//! each store's build number must follow it, or a store refuses the upload.
use std::path::Path;

fn read(path: &str) -> String {
    std::fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join("../..").join(path)).unwrap()
}

/// The value after `key` on its line, without quotes.
fn value(text: &str, key: &str) -> String {
    let line = text.lines().find(|l| l.trim_start().starts_with(key)).unwrap_or_else(|| panic!("no {key}"));
    line.split_once(key).unwrap().1.trim_start_matches([' ', '=', ':']).trim().trim_matches(['"', '\'', ',']).to_string()
}

/// 0.8.0 → 800: what both stores get as build number.
fn build_number(version: &str) -> u32 {
    let parts: Vec<u32> = version.split('.').map(|p| p.parse().unwrap()).collect();
    parts[0] * 10_000 + parts[1] * 100 + parts[2]
}

#[test]
fn every_version_is_the_workspace_one() {
    let version = env!("CARGO_PKG_VERSION");
    assert_eq!(value(&read("android/app/build.gradle.kts"), "versionName"), version);
    let ios = read("ios/project.yml");
    assert_eq!(value(&ios, "MARKETING_VERSION"), version);
    assert_eq!(value(&ios, "CURRENT_PROJECT_VERSION"), build_number(version).to_string());
    let gateway = read("pxe/host/src/dapp/gateway.ts");
    assert!(gateway.contains(&format!("walletVersion: '{version}'")), "the dApp gateway says another version");
}

#[test]
fn android_derives_its_build_number_from_the_version() {
    assert_eq!(build_number("0.8.0"), 800);
    assert_eq!(build_number("1.2.3"), 10_203);
    assert!(read("android/app/build.gradle.kts").contains("major * 10_000 + minor * 100 + patch"));
}
