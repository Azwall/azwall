//! Azwall is Apache-2.0: the license file, the notice and every package's declaration must say the same.
use std::path::Path;

fn read(path: &str) -> String {
    std::fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join("../..").join(path)).unwrap()
}

#[test]
fn every_package_declares_the_license_of_the_license_file() {
    assert!(read("LICENSE").contains("Apache License\n                           Version 2.0, January 2004"));
    assert!(read("NOTICE").contains("Apache License, Version 2.0"));
    assert_eq!(env!("CARGO_PKG_LICENSE"), "Apache-2.0");
    for package in ["pxe/package.json", "pxe/shared/package.json", "pxe/v6/package.json"] {
        assert!(read(package).contains(r#""license": "Apache-2.0""#), "{package} declares another license");
    }
}
