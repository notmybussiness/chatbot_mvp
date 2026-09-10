from .base import Notifier, NotifyError
from .stdout import StdoutNotifier
from .telegram import TelegramNotifier

__all__ = ["Notifier", "NotifyError", "StdoutNotifier", "TelegramNotifier", "build_notifier"]


def build_notifier(channel: str, secrets: dict[str, str], *, dry_run: bool = False) -> Notifier:
    if dry_run or channel == "stdout":
        return StdoutNotifier()
    if channel == "telegram":
        return TelegramNotifier(token=secrets["TELEGRAM_BOT_TOKEN"], chat_id=secrets["TELEGRAM_CHAT_ID"])
    raise ValueError(f"지원하지 않는 알림 채널: {channel} (stdout | telegram)")
