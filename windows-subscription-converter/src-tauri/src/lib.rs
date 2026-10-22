use percent_encoding::percent_decode_str;
use reqwest::{
    Client, Response, StatusCode,
    header::{ACCEPT, USER_AGENT},
    redirect::Policy,
};
use serde::Serialize;
use std::{io, path::PathBuf, time::Duration};
use url::Url;

const MAX_SUBSCRIPTION_BYTES: usize = 2 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 64 * 1024 * 1024;
const MAX_REDIRECTS: usize = 10;
const MAX_URL_CHARS: usize = 8192;
const DEFAULT_USER_AGENTS: &[&str] = &[
    "NekoBox/Android/1.4.2 (Prefer ClashMeta Format)",
    "sing-box/1.15.0",
    "clash-meta/1.19.0",
    "v2rayN/7.8.2",
];

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct FetchedSubscription {
    content: String,
    final_url: String,
    content_type: Option<String>,
    subscription_user_info: Option<String>,
    bytes: usize,
    used_user_agent: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct FetchFailure {
    code: String,
    message: String,
    status: Option<u16>,
}

impl FetchFailure {
    fn new(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            status: None,
        }
    }

    fn with_status(
        code: impl Into<String>,
        message: impl Into<String>,
        status: StatusCode,
    ) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            status: Some(status.as_u16()),
        }
    }
}

fn parse_target(input: &str) -> Result<(Url, Option<(String, String)>), FetchFailure> {
    let value = input.trim();
    if value.is_empty() {
        return Err(FetchFailure::new("invalid_url", "订阅 URL 不能为空。"));
    }
    if value.chars().count() > MAX_URL_CHARS || value.contains(['\r', '\n']) {
        return Err(FetchFailure::new(
            "invalid_url",
            "订阅 URL 过长或包含换行。",
        ));
    }

    let mut url =
        Url::parse(value).map_err(|_| FetchFailure::new("invalid_url", "订阅 URL 格式无效。"))?;
    if !matches!(url.scheme(), "http" | "https") {
        return Err(FetchFailure::new(
            "unsupported_scheme",
            "仅支持 HTTP 或 HTTPS 订阅地址。",
        ));
    }
    if url.host_str().is_none() {
        return Err(FetchFailure::new("invalid_url", "订阅 URL 缺少主机名。"));
    }

    // URL 中的 userinfo 只转换为 Basic Auth，不保留在请求地址和返回信息中。
    let username = percent_decode_str(url.username())
        .decode_utf8_lossy()
        .into_owned();
    let password = url
        .password()
        .map(|value| percent_decode_str(value).decode_utf8_lossy().into_owned());
    let auth = if username.is_empty() {
        None
    } else {
        Some((username, password.unwrap_or_default()))
    };

    url.set_username("")
        .map_err(|_| FetchFailure::new("invalid_url", "无法清理订阅 URL 中的账号信息。"))?;
    url.set_password(None)
        .map_err(|_| FetchFailure::new("invalid_url", "无法清理订阅 URL 中的密码信息。"))?;
    url.set_fragment(None);
    Ok((url, auth))
}

fn user_agents(custom: Option<&str>) -> Result<Vec<String>, FetchFailure> {
    let Some(custom) = custom.map(str::trim).filter(|value| !value.is_empty()) else {
        return Ok(DEFAULT_USER_AGENTS
            .iter()
            .map(|value| (*value).to_string())
            .collect());
    };
    if custom.chars().count() > 512 || custom.chars().any(char::is_control) {
        return Err(FetchFailure::new(
            "invalid_user_agent",
            "User-Agent 格式无效。",
        ));
    }
    Ok(vec![custom.to_string()])
}

fn build_client(skip_certificate_verification: bool) -> Result<Client, FetchFailure> {
    let redirect_policy = Policy::custom(|attempt| {
        if attempt.previous().len() >= MAX_REDIRECTS {
            attempt.error(io::Error::new(io::ErrorKind::InvalidData, "重定向次数过多"))
        } else if !matches!(attempt.url().scheme(), "http" | "https") {
            attempt.error(io::Error::new(
                io::ErrorKind::InvalidData,
                "重定向目标不是 HTTP(S)",
            ))
        } else {
            attempt.follow()
        }
    });

    let mut builder = Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(30))
        .redirect(redirect_policy);

    if skip_certificate_verification {
        builder = builder
            .danger_accept_invalid_certs(true)
            .danger_accept_invalid_hostnames(true);
    }

    builder
        .build()
        .map_err(|_| FetchFailure::new("client_init_failed", "无法初始化本机网络客户端。"))
}

fn sanitized_display_url(url: &Url) -> String {
    let mut value = url.clone();
    let _ = value.set_username("");
    let _ = value.set_password(None);
    value.set_fragment(None);
    value.to_string()
}

fn network_failure(error: &reqwest::Error) -> FetchFailure {
    if error.is_timeout() {
        FetchFailure::new("timeout", "订阅请求超时，请检查网络或稍后重试。")
    } else if error.is_connect() {
        FetchFailure::new("connect_failed", "无法连接订阅服务器，请检查地址和网络。")
    } else if error.is_redirect() {
        FetchFailure::new("redirect_failed", "订阅地址重定向失败或次数超过限制。")
    } else {
        FetchFailure::new("network_error", "订阅请求失败，请检查网络连接。")
    }
}

fn http_failure(status: StatusCode) -> FetchFailure {
    let message = match status.as_u16() {
        401 => "订阅服务器要求认证，请检查 URL 中的账号密码。",
        403 => "订阅服务器拒绝访问（HTTP 403）。",
        404 => "订阅地址不存在（HTTP 404）。",
        429 => "订阅服务器请求过于频繁（HTTP 429），请稍后重试。",
        500..=599 => "订阅服务器暂时不可用，请稍后重试。",
        _ => "订阅服务器返回了非成功状态。",
    };
    FetchFailure::with_status("http_error", message, status)
}

fn should_retry_with_next_user_agent(status: StatusCode) -> bool {
    matches!(status.as_u16(), 400 | 403 | 406 | 415)
}

async fn read_limited_response(mut response: Response) -> Result<Vec<u8>, FetchFailure> {
    if response
        .content_length()
        .is_some_and(|length| length > MAX_SUBSCRIPTION_BYTES as u64)
    {
        return Err(FetchFailure::new(
            "response_too_large",
            "订阅响应超过 2 MiB 限制。",
        ));
    }

    let mut body = Vec::with_capacity(
        response
            .content_length()
            .unwrap_or(0)
            .min(MAX_SUBSCRIPTION_BYTES as u64) as usize,
    );
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|error| network_failure(&error))?
    {
        if body.len().saturating_add(chunk.len()) > MAX_SUBSCRIPTION_BYTES {
            return Err(FetchFailure::new(
                "response_too_large",
                "订阅响应超过 2 MiB 限制。",
            ));
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body)
}

async fn fetch_subscription_inner(
    raw_url: &str,
    custom_user_agent: Option<&str>,
    skip_certificate_verification: bool,
) -> Result<FetchedSubscription, FetchFailure> {
    let (url, basic_auth) = parse_target(raw_url)?;
    let agents = user_agents(custom_user_agent)?;
    let client = build_client(skip_certificate_verification)?;
    let agent_count = agents.len();
    let mut last_status = None;

    for (index, agent) in agents.iter().enumerate() {
        let mut request = client
            .get(url.clone())
            .header(USER_AGENT, agent)
            .header(ACCEPT, "*/*");
        if let Some((username, password)) = &basic_auth {
            request = request.basic_auth(username, Some(password));
        }

        let response = request
            .send()
            .await
            .map_err(|error| network_failure(&error))?;
        let status = response.status();
        last_status = Some(status);

        if status.is_success() {
            let content_type = response
                .headers()
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|value| value.to_str().ok())
                .map(str::to_string);
            let subscription_user_info = response
                .headers()
                .get("subscription-userinfo")
                .and_then(|value| value.to_str().ok())
                .map(str::to_string);
            let final_url = sanitized_display_url(response.url());
            let body = read_limited_response(response).await?;

            if body.is_empty() {
                if index + 1 < agent_count {
                    continue;
                }
                return Err(FetchFailure::new(
                    "empty_response",
                    "订阅服务器返回了空内容。",
                ));
            }

            let content = String::from_utf8_lossy(&body)
                .trim_start_matches('\u{feff}')
                .to_string();
            return Ok(FetchedSubscription {
                content,
                final_url,
                content_type,
                subscription_user_info,
                bytes: body.len(),
                used_user_agent: agent.clone(),
            });
        }

        if index + 1 < agent_count && should_retry_with_next_user_agent(status) {
            continue;
        }
        return Err(http_failure(status));
    }

    Err(match last_status {
        Some(status) => http_failure(status),
        None => FetchFailure::new("network_error", "订阅请求失败，请稍后重试。"),
    })
}
#[tauri::command]
async fn fetch_subscription(
    url: String,
    user_agent: Option<String>,
    skip_certificate_verification: Option<bool>,
) -> Result<FetchedSubscription, FetchFailure> {
    fetch_subscription_inner(
        &url,
        user_agent.as_deref(),
        skip_certificate_verification.unwrap_or(false),
    )
    .await
}

#[tauri::command]
fn save_text_file(path: String, content: String) -> Result<String, FetchFailure> {
    if content.len() > MAX_OUTPUT_BYTES {
        return Err(FetchFailure::new(
            "output_too_large",
            "输出内容超过 64 MiB，无法保存。",
        ));
    }

    let target = PathBuf::from(path.trim());
    if !target.is_absolute() {
        return Err(FetchFailure::new(
            "invalid_path",
            "保存路径必须是绝对路径。",
        ));
    }
    let is_txt = target
        .extension()
        .and_then(|value| value.to_str())
        .is_some_and(|value| value.eq_ignore_ascii_case("txt"));
    if !is_txt {
        return Err(FetchFailure::new(
            "invalid_path",
            "仅支持保存为 .txt 文件。",
        ));
    }

    std::fs::write(&target, content)
        .map_err(|_| FetchFailure::new("write_failed", "写入文件失败，请检查保存位置。"))?;
    Ok(target.to_string_lossy().into_owned())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![fetch_subscription, save_text_file])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::TcpListener,
    };

    async fn serve_once(body: &'static [u8], content_type: &'static str) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buffer = [0_u8; 4096];
            let _ = socket.read(&mut buffer).await;
            let header = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            );
            socket.write_all(header.as_bytes()).await.unwrap();
            socket.write_all(body).await.unwrap();
        });
        format!("http://{address}/subscription")
    }

    async fn serve_once_tls(body: &'static [u8], content_type: &'static str) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let rcgen::CertifiedKey { cert, signing_key } =
            rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).unwrap();
        let key_der =
            tokio_rustls::rustls::pki_types::PrivatePkcs8KeyDer::from(signing_key.serialize_der());
        let config = tokio_rustls::rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![cert.der().clone()], key_der.into())
            .unwrap();
        let acceptor = tokio_rustls::TlsAcceptor::from(std::sync::Arc::new(config));

        tokio::spawn(async move {
            let (socket, _) = listener.accept().await.unwrap();
            let mut socket = acceptor.accept(socket).await.unwrap();
            let mut buffer = [0_u8; 4096];
            let _ = socket.read(&mut buffer).await;
            let header = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            );
            socket.write_all(header.as_bytes()).await.unwrap();
            socket.write_all(body).await.unwrap();
        });

        format!("https://localhost:{}/subscription", address.port())
    }

    #[test]
    fn parses_basic_auth_without_keeping_credentials_in_url() {
        let (url, auth) =
            parse_target("https://user%40name:p%40ss@example.com/sub?token=abc").unwrap();
        assert_eq!(url.as_str(), "https://example.com/sub?token=abc");
        assert_eq!(auth, Some(("user@name".to_string(), "p@ss".to_string())));
    }

    #[tokio::test]
    async fn rejects_self_signed_certificate_by_default() {
        let url = serve_once_tls(b"vless://node@example.com:443", "text/plain").await;
        let result = fetch_subscription_inner(&url, Some("TestClient/1.0"), false).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn allows_self_signed_certificate_when_verification_is_skipped() {
        let url = serve_once_tls(b"vless://node@example.com:443", "text/plain").await;
        let result = fetch_subscription_inner(&url, Some("TestClient/1.0"), true)
            .await
            .unwrap();
        assert_eq!(result.content, "vless://node@example.com:443");
    }

    #[test]
    fn rejects_non_http_schemes() {
        let failure = parse_target("file:///C:/secret.txt").unwrap_err();
        assert_eq!(failure.code, "unsupported_scheme");
    }

    #[tokio::test]
    async fn fetches_plain_text_subscription() {
        let url = serve_once(b"vless://node@example.com:443", "text/plain").await;
        let result = fetch_subscription_inner(&url, Some("TestClient/1.0"), false)
            .await
            .unwrap();
        assert_eq!(result.content, "vless://node@example.com:443");
        assert_eq!(result.used_user_agent, "TestClient/1.0");
        assert_eq!(result.bytes, 28);
    }

    #[tokio::test]
    async fn rejects_oversized_response() {
        static BODY: [u8; MAX_SUBSCRIPTION_BYTES + 1] = [b'a'; MAX_SUBSCRIPTION_BYTES + 1];
        let url = serve_once(&BODY, "text/plain").await;
        let failure = fetch_subscription_inner(&url, Some("TestClient/1.0"), false)
            .await
            .unwrap_err();
        assert_eq!(failure.code, "response_too_large");
    }

    #[test]
    fn only_saves_txt_files() {
        let target = std::env::temp_dir().join(format!(
            "chimera-subscription-test-{}.bin",
            std::process::id()
        ));
        let failure =
            save_text_file(target.to_string_lossy().into_owned(), "test".to_string()).unwrap_err();
        assert_eq!(failure.code, "invalid_path");
    }

    #[test]
    fn saves_utf8_txt_file() {
        let target = std::env::temp_dir().join(format!(
            "chimera-subscription-test-{}.txt",
            std::process::id()
        ));
        let path = target.to_string_lossy().into_owned();
        save_text_file(path.clone(), "vless://测试节点".to_string()).unwrap();
        assert_eq!(
            std::fs::read_to_string(std::path::Path::new(&path)).unwrap(),
            "vless://测试节点"
        );
        let _ = std::fs::remove_file(target);
    }
}
