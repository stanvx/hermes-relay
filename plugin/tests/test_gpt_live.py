from __future__ import annotations

import asyncio
import base64
import json
import os

import httpx
import pytest

from plugin.relay import gpt_live
from plugin.relay.realtime_agent.providers.openai import AuthToken


OFFER = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
ANSWER = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"


def _oauth_token(account_id: str = "acct_test") -> str:
    payload = {
        "https://api.openai.com/auth": {
            "chatgpt_account_id": account_id,
        }
    }
    encoded = base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip("=")
    return f"header.{encoded}.signature"


def test_readiness_uses_codex_subscription_without_api_key(monkeypatch):
    monkeypatch.delenv("RELAY_GPT_LIVE_SUBSCRIPTION_MODEL", raising=False)
    monkeypatch.delenv("RELAY_GPT_LIVE_SUBSCRIPTION_VOICE", raising=False)
    monkeypatch.delenv("TALK_LIVE_SUBSCRIPTION_MODEL", raising=False)
    monkeypatch.delenv("TALK_LIVE_SUBSCRIPTION_VOICE", raising=False)
    monkeypatch.setattr(
        gpt_live,
        "_resolve_codex_oauth_token",
        lambda: AuthToken(_oauth_token(), "codex-cli:chatgpt"),
    )
    state = gpt_live.readiness()
    assert state.available is True
    assert state.auth_source == "codex-cli:chatgpt"
    assert state.model == "gpt-live-1-codex"
    assert state.voice == "cove"


def test_create_session_posts_quicksilver_subscription_call(monkeypatch):
    token = _oauth_token("acct_123")
    monkeypatch.setattr(
        gpt_live,
        "_resolve_codex_oauth_token",
        lambda: AuthToken(token, "hermes:openai-codex"),
    )
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(
            201,
            text=ANSWER,
            headers={"openai-session-id": "rtc_test123"},
        )

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            return await gpt_live.create_session(
                OFFER,
                [{"role": "user", "text": "history stays in Hermes"}],
                client=client,
            )

    result = asyncio.run(run())

    request = captured["request"]
    assert str(request.url) == gpt_live.SUBSCRIPTION_CALL_URL
    assert request.headers["authorization"] == f"Bearer {token}"
    assert request.headers["openai-alpha"] == "quicksilver=v2"
    assert request.headers["chatgpt-account-id"] == "acct_123"
    payload = json.loads(request.content)
    assert payload["sdp"] == OFFER
    assert payload["session"]["model"] == "gpt-live-1-codex"
    assert payload["session"]["delegation"] == {"type": "client"}
    assert "input" not in payload["session"]
    assert result["session"]["id"] == "rtc_test123"
    assert result["transport"]["sdp"] == ANSWER
    assert result["event_dialect"] == "subscription"
    assert result["auth_source"] == "hermes:openai-codex"


def test_create_session_fails_closed_without_codex(monkeypatch):
    monkeypatch.setattr(gpt_live, "_resolve_codex_oauth_token", lambda: None)
    with pytest.raises(gpt_live.GptLiveUnavailable, match="API-key fallback was not attempted"):
        asyncio.run(gpt_live.create_session(OFFER))


def test_create_session_fails_closed_without_account_id(monkeypatch):
    monkeypatch.setattr(
        gpt_live,
        "_resolve_codex_oauth_token",
        lambda: AuthToken("not-a-jwt", "codex-cli:chatgpt"),
    )
    with pytest.raises(gpt_live.GptLiveUnavailable, match="account id"):
        asyncio.run(gpt_live.create_session(OFFER))


def test_create_session_does_not_echo_provider_body(monkeypatch):
    token = _oauth_token()
    monkeypatch.setattr(
        gpt_live,
        "_resolve_codex_oauth_token",
        lambda: AuthToken(token, "codex-cli:chatgpt"),
    )

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(403, text=f"sensitive upstream detail {token}")

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            return await gpt_live.create_session(OFFER, client=client)

    with pytest.raises(gpt_live.GptLiveRejected) as error:
        asyncio.run(run())
    assert token not in str(error.value)


def test_usage_limit_is_reported_as_429(monkeypatch):
    token = _oauth_token()
    monkeypatch.setattr(
        gpt_live,
        "_resolve_codex_oauth_token",
        lambda: AuthToken(token, "codex-cli:chatgpt"),
    )

    async def run():
        transport = httpx.MockTransport(lambda request: httpx.Response(429, text="limit"))
        async with httpx.AsyncClient(transport=transport) as client:
            return await gpt_live.create_session(OFFER, client=client)

    with pytest.raises(gpt_live.GptLiveRejected, match="usage limit") as error:
        asyncio.run(run())
    assert error.value.upstream_status == 429


@pytest.mark.parametrize("offer", [None, "", "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n", "v=0\r\n", "v=0\r\nm=audio \x00"])
def test_invalid_offer_is_rejected_before_authentication(monkeypatch, offer):
    def unexpected_auth():
        pytest.fail("Invalid SDP must not resolve credentials")
    monkeypatch.setattr(gpt_live, "_resolve_codex_oauth_token", unexpected_auth)
    with pytest.raises(ValueError):
        asyncio.run(gpt_live.create_session(offer))


def test_bad_provider_sdp_is_a_provider_failure(monkeypatch):
    monkeypatch.setattr(gpt_live, "_resolve_codex_oauth_token", lambda: AuthToken(_oauth_token(), "test"))
    async def run():
        transport = httpx.MockTransport(lambda request: httpx.Response(201, text="invalid", headers={"openai-session-id": "rtc_test"}))
        async with httpx.AsyncClient(transport=transport) as client:
            return await gpt_live.create_session(OFFER, client=client)
    with pytest.raises(gpt_live.GptLiveRejected, match="invalid SDP answer"):
        asyncio.run(run())


def _codex_home(tmp_path, name: str, account_id: str):
    home = tmp_path / name
    home.mkdir()
    (home / "auth.json").write_text(
        json.dumps({"auth_mode": "chatgpt", "tokens": {"access_token": _oauth_token(account_id)}}),
        encoding="utf-8",
    )
    return home


def test_usage_limit_moves_to_next_codex_account(monkeypatch, tmp_path):
    first = _codex_home(tmp_path, "first", "acct_first")
    second = _codex_home(tmp_path, "second", "acct_second")
    monkeypatch.setenv(gpt_live.CODEX_HOMES_ENV, f"{first}{os.pathsep}{second}")
    monkeypatch.setattr(gpt_live, "_limited_until", {})
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        account = request.headers["chatgpt-account-id"]
        seen.append(account)
        if account == "acct_first":
            return httpx.Response(429, text="limit")
        return httpx.Response(201, text=ANSWER, headers={"openai-session-id": "rtc_test"})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            return await gpt_live.create_session(OFFER, client=client)

    assert asyncio.run(run())["transport"]["sdp"] == ANSWER
    assert seen == ["acct_first", "acct_second"]
    # The limited account rests; the next session goes straight to the second.
    seen.clear()
    asyncio.run(run())
    assert seen == ["acct_second"]


def test_all_accounts_limited_is_unavailable(monkeypatch, tmp_path):
    only = _codex_home(tmp_path, "only", "acct_only")
    monkeypatch.setenv(gpt_live.CODEX_HOMES_ENV, str(only))
    monkeypatch.setattr(gpt_live, "_limited_until", {})

    async def run():
        transport = httpx.MockTransport(lambda request: httpx.Response(429, text="limit"))
        async with httpx.AsyncClient(transport=transport) as client:
            return await gpt_live.create_session(OFFER, client=client)

    with pytest.raises(gpt_live.GptLiveRejected, match="usage limit"):
        asyncio.run(run())
    with pytest.raises(gpt_live.GptLiveUnavailable, match="resting after its usage limit"):
        asyncio.run(run())
    assert gpt_live.readiness().available is False
