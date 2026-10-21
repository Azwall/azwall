//! Minimal Aztec node JSON-RPC client and the node compatibility check: before using a network,
//! the app asks its node for `node_getNodeInfo` and compares it with the registry's expectations.

use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::network::{major_version, NetworkSpec};
use crate::{CoreError, Result};

/// Sends a JSON body to a URL and returns the response body. A trait so tests can stub the node.
pub trait Transport: Send + Sync {
    fn post_json(&self, url: &str, body: &str) -> Result<String>;
}

pub struct HttpTransport {
    agent: ureq::Agent,
}

impl HttpTransport {
    pub fn new(timeout: Duration) -> Self {
        Self { agent: ureq::AgentBuilder::new().timeout(timeout).build() }
    }
}

impl Default for HttpTransport {
    fn default() -> Self {
        Self::new(Duration::from_secs(20))
    }
}

impl Transport for HttpTransport {
    fn post_json(&self, url: &str, body: &str) -> Result<String> {
        let response = self
            .agent
            .post(url)
            .set("content-type", "application/json")
            .send_string(body)
            .map_err(|e| CoreError::Rpc(redact_urls(&e.to_string())))?;
        response.into_string().map_err(|e| CoreError::Rpc(redact_urls(&e.to_string())))
    }
}

/// RPC URLs often carry an API key in the path: keep only the scheme and host of every URL in an error message,
/// whatever form the HTTP client gave it (it may normalise the URL it quotes).
pub fn redact_urls(message: &str) -> String {
    let mut out = String::with_capacity(message.len());
    let mut rest = message;
    while let Some(i) = rest.find("http") {
        let after = &rest[i..];
        let scheme = ["https://", "http://"].into_iter().find(|s| after.starts_with(s)).map(str::len);
        let Some(scheme) = scheme else {
            out.push_str(&rest[..i + 4]);
            rest = &rest[i + 4..];
            continue;
        };
        out.push_str(&rest[..i]);
        let end = after.find(|c: char| c.is_whitespace() || "\"'<>()".contains(c)).unwrap_or(after.len());
        let url = after[..end].trim_end_matches(['.', ',', ';', ':', '!']);
        let host_end = url[scheme..].find(['/', '?', '#']).map_or(url.len(), |p| scheme + p);
        out.push_str(&url[..host_end]);
        if host_end < url.len() {
            out.push_str("/…");
        }
        rest = &after[url.len()..];
    }
    out.push_str(rest);
    out
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NodeInfo {
    pub node_version: String,
    pub l1_chain_id: u64,
    pub rollup_version: u64,
}

pub struct NodeClient<T: Transport> {
    transport: T,
    url: String,
}

impl<T: Transport> NodeClient<T> {
    pub fn new(transport: T, url: impl Into<String>) -> Self {
        Self { transport, url: url.into() }
    }

    pub fn call(&self, method: &str, params: Value) -> Result<Value> {
        let body = json!({ "jsonrpc": "2.0", "id": 1, "method": method, "params": params }).to_string();
        let text = self.transport.post_json(&self.url, &body)?;
        let mut reply: Value =
            serde_json::from_str(&text).map_err(|e| CoreError::Rpc(format!("{method}: invalid JSON response: {e}")))?;
        if let Some(error) = reply.get("error").filter(|e| !e.is_null()) {
            let message = error.get("message").and_then(Value::as_str).unwrap_or("unknown error");
            return Err(CoreError::Rpc(format!("{method}: {message}")));
        }
        reply.get_mut("result").map(Value::take).ok_or_else(|| CoreError::Rpc(format!("{method}: no result")))
    }

    pub fn node_info(&self) -> Result<NodeInfo> {
        let result = self.call("node_getNodeInfo", json!([]))?;
        serde_json::from_value(result).map_err(|e| CoreError::Rpc(format!("node_getNodeInfo: {e}")))
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Compatibility {
    pub node: NodeInfo,
    /// Human-readable reasons the node does not match the network; empty when compatible.
    pub mismatches: Vec<String>,
}

impl Compatibility {
    pub fn is_compatible(&self) -> bool {
        self.mismatches.is_empty()
    }
}

/// Compares a node with the network it is configured for. The node's major version must match
/// the client's (minor/patch may differ: the v5 testnet runs node 5.0.0 with 5.1.0 clients).
pub fn check_compatibility(spec: &NetworkSpec, node: NodeInfo) -> Compatibility {
    let mut mismatches = Vec::new();
    if node.l1_chain_id != spec.expected.l1_chain_id {
        mismatches.push(format!("L1 chain id {} (expected {})", node.l1_chain_id, spec.expected.l1_chain_id));
    }
    if node.rollup_version != spec.expected.rollup_version {
        mismatches.push(format!("rollup version {} (expected {})", node.rollup_version, spec.expected.rollup_version));
    }
    if major_version(&node.node_version) != spec.aztec_major() {
        mismatches.push(format!("node version {} (client {})", node.node_version, spec.aztec));
    }
    Compatibility { node, mismatches }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::network::Expected;
    use std::sync::Mutex;

    struct Canned {
        reply: String,
        seen: Mutex<Vec<String>>,
    }

    impl Transport for &Canned {
        fn post_json(&self, _url: &str, body: &str) -> Result<String> {
            self.seen.lock().unwrap().push(body.to_string());
            Ok(self.reply.clone())
        }
    }

    fn canned(reply: &str) -> Canned {
        Canned { reply: reply.to_string(), seen: Mutex::new(Vec::new()) }
    }

    fn spec() -> NetworkSpec {
        NetworkSpec {
            id: "testnet-v6".into(),
            label: "Testnet".into(),
            aztec: "6.0.0-rc.3".into(),
            barretenberg: "6.0.0-rc.6".into(),
            noir: None,
            pxe: "v6".into(),
            default_rpc: None,
            expected: Expected { l1_chain_id: 11155111, rollup_version: 3409706519, vk_tree_root: None, protocol_contracts_hash: None },
            tokens: Vec::new(),
            dapps: Vec::new(),
        }
    }

    const NODE_INFO: &str = r#"{"jsonrpc":"2.0","id":1,"result":{"nodeVersion":"6.0.0-rc.3","l1ChainId":11155111,
        "rollupVersion":3409706519,"enr":"enr:-x","realProofs":true}}"#;

    #[test]
    fn sends_a_json_rpc_request_and_parses_node_info() {
        let node = canned(NODE_INFO);
        let info = NodeClient::new(&node, "https://node").node_info().unwrap();
        assert_eq!(info.rollup_version, 3409706519);
        let sent: Value = serde_json::from_str(&node.seen.lock().unwrap()[0]).unwrap();
        assert_eq!(sent["method"], "node_getNodeInfo");
        assert_eq!(sent["jsonrpc"], "2.0");
    }

    #[test]
    fn surfaces_rpc_errors() {
        let node = canned(r#"{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"method not found"}}"#);
        let err = NodeClient::new(&node, "https://node").node_info().unwrap_err();
        assert!(err.to_string().contains("method not found"));
        assert!(NodeClient::new(&canned("<html>"), "https://node").node_info().is_err());
        assert!(NodeClient::new(&canned(r#"{"id":1}"#), "https://node").node_info().is_err());
    }

    #[test]
    fn a_matching_node_is_compatible() {
        let info: NodeInfo = serde_json::from_value(serde_json::from_str::<Value>(NODE_INFO).unwrap()["result"].clone()).unwrap();
        assert!(check_compatibility(&spec(), info).is_compatible());
    }

    #[test]
    fn reports_every_mismatch() {
        let node = NodeInfo { node_version: "5.0.0".into(), l1_chain_id: 1, rollup_version: 1821665230 };
        let compat = check_compatibility(&spec(), node);
        assert_eq!(compat.mismatches.len(), 3, "{:?}", compat.mismatches);
    }

    #[test]
    fn redacts_keys_from_errors() {
        let message = redact_urls("https://lb.drpc.org/aztec-testnet/SECRET: Connection refused");
        assert_eq!(message, "https://lb.drpc.org/…: Connection refused");
        // Any URL, as the client normalised it, and text around it untouched.
        assert_eq!(redact_urls("POST http://n.io:8080/v1?key=K (status 500); httpd"), "POST http://n.io:8080/… (status 500); httpd");
        assert_eq!(redact_urls("node https://rpc.example.org is down"), "node https://rpc.example.org is down");
    }
}
