# flight-deal-alert — 항공권 특가 알림

여러 소스에서 항공권 가격을 주기적으로 모아, **내 기준보다 싸게 나온 것만 골라** Telegram 으로 알림을 보내는 개인 도구입니다.
핵심 가치는 두 가지입니다: **빨리 알아채는 것**, 그리고 **알림이 쓸모없어지지 않는 것**(스팸 금지).

```
[Source Adapters] → Offer(정규화) → [SQLite] → [Deal Detector] → [Alert Dispatcher] → Telegram
 mock / travelpayouts / modetour / ddaeng / naver / (skyscanner: 스텁)
```

> 이 문서에서 **검증 안 됨** 이라고 적힌 것은 실제 사이트/API 를 호출해 확인하지 못한 부분입니다.
> 코드가 있다고 동작이 보장되는 게 아니므로, 반드시 `diagnose` 로 확인한 뒤 켜세요.

## 1. 5분 안에 돌려보기 (외부 호출 없음)

```bash
cd flight-deal-alert
pip install -e ".[dev]"
cp config.example.yaml config.yaml      # 기본값: mock 소스만 켜짐, 알림은 화면 출력
python -m flight_deal_alert run --dry-run --force
python -m flight_deal_alert report --dry-run
python -m pytest -q                     # 72 tests
```

`run` 을 두 번 실행하면 두 번째는 "이미 알림 보냄, 더 싸지지 않음" 으로 억제되는 걸 볼 수 있습니다. 이게 스팸 방지입니다.

## 2. Telegram 으로 받기

1. Telegram 에서 `@BotFather` 에게 `/newbot` → 토큰 발급
2. 만든 봇에게 아무 메시지나 보낸 뒤 `https://api.telegram.org/bot<토큰>/getUpdates` 를 열어 `chat.id` 확인
3. `.env.example` 을 `.env` 로 복사하고 두 값을 채움
4. `config.yaml` 의 `notify.channel` 을 `telegram` 으로
5. `python -m flight_deal_alert daemon` — 상주 실행 (매 분 깨어나 주기가 된 소스만 돌림)

토큰은 `.env` 에만 둡니다. 코드나 `config.yaml` 에 넣지 마세요.

## 3. 설정 (`config.yaml`)

| 항목 | 뜻 |
|---|---|
| `routes[].budget_krw` | **절대 기준.** 이 값 이하면 무조건 알림 후보. **꼭 채우세요** — 비어 있으면 첫 며칠은 알림이 거의 안 옵니다 |
| `routes[].depart_from / depart_to` | 출발일 창 |
| `routes[].stay_days: [3, 7]` | 왕복 체류일 범위. 지우면 편도 |
| `destination_aliases` | 땡처리 목록은 "도쿄" 처럼 도시명만 있어서 공항코드로 매핑할 별칭이 필요 |
| `detection.relative_discount_pct` | 최근 30일 중앙값 대비 이만큼 싸면 특가 (기본 20%) |
| `detection.relative_min_observations` | 관측이 이보다 적으면 상대 기준을 **아예 적용하지 않음** (기본 10) |
| `detection.max_alerts_per_route_per_day` | 노선당 하루 알림 한도 (기본 5) |
| `detection.quiet_hours: [0, 7]` | 이 시간대엔 모아뒀다 아침에 한 통으로. 긴급도 90 이상은 즉시 |
| `sources.<이름>.enabled` | 소스 켜기/끄기 |
| `sources.<이름>.interval_minutes` | 소스별 확인 주기 |

## 4. 특가 판정 규칙

하나라도 걸리면 후보. 알림에는 **왜 특가인지** 가 규칙별로 한 줄씩 적힙니다.

| 규칙 | 조건 | 비고 |
|---|---|---|
| `absolute` | 가격 ≤ 노선 예산 | 첫날부터 동작 |
| `relative` | 가격 ≤ 최근 30일 중앙값 × 0.8 | 관측 10건 이상일 때만. **기준선 없이 "싸다" 고 하지 않음** |
| `all_time` | 가격 < 관측 이래 최저 | 이전 관측이 있을 때만 |
| `new_tagged` | 땡처리/공동구매/특가 태그 항목이 **처음** 등장 | 같은 항목 재등장은 무시 |
| `drop` | 같은 항목이 직전 대비 15% 이상 하락 | |

**긴급도(0~100)**: 절대+상대 동시 충족 > 역대 최저 > 출발 임박 땡처리 > 단일 규칙. 규칙이 겹치면 가산.

**스팸 방지**: 같은 항목은 가격이 더 내려가지 않는 한 재알림 안 함 · 노선당 일일 한도 · 배치 내 중복은 최저가 하나만 · 조용한 시간대 모아 보내기.

## 5. 데이터 소스 — 현실과 붙이는 법

| 소스 | 상태 | 어떻게 |
|---|---|---|
| `mock` | ✅ 검증됨 | 외부 호출 없음. 파이프라인 확인용 |
| `travelpayouts` | ⚠️ 문서 기준 구현, 실호출 미검증 | 공식 API. [가입](https://www.travelpayouts.com) 후 `.env` 에 `TRAVELPAYOUTS_TOKEN`. 캐시 가격이라 "지금 이 값에 산다" 보다 "요즘 이 정도" 에 가까움 |
| `modetour` | ⚠️ 검증 안 됨 | 공동구매 목록 페이지 HTML. 아래 5.1 |
| `ddaeng` | ⚠️ 검증 안 됨 | 땡처리 목록 페이지 HTML. 아래 5.1 |
| `naver` | ⚠️ 검증 안 됨 + 배포 시 차단 가능 | 비공식 내부 API. 아래 5.2 |
| `skyscanner` | ❌ 스텁 | 파트너 심사 + 상업 계약 필요. 키 없이는 동작 불가 |

`Amadeus Self-Service` 는 2026-07-17 셀프서비스 포털이 종료되어 쓰지 않습니다.

모든 소스는 **어댑터 하나 = 파일 하나** 이고, 한 소스가 죽어도 나머지는 계속 돕니다. 실패는 요약 리포트에 `❌` 로 표시됩니다.

### 5.1 HTML 목록 페이지 (modetour / ddaeng) 맞추기

두 소스는 같은 어댑터(`html_list`)를 쓰고, **선택자가 전부 `config.yaml` 에 있습니다.** 코드 수정 없이 맞춥니다.

```bash
python -m flight_deal_alert diagnose modetour
```

출력에서 볼 것:
- `item_selector '...' 에 N개 걸림` — 0개면 선택자가 틀렸거나 페이지가 JS 로 렌더링됨 (응답 앞부분을 보면 알 수 있음)
- `관심 노선 미매핑 N개` — 전부 미매핑이면 `destination_aliases` 에 목록에 쓰인 도시명을 추가
- `가격 파싱 실패 N개` — `fields.price.selector` / `regex` 를 확인

브라우저 DevTools 로 목록 한 줄의 구조를 보고 `item_selector`, `fields.title/price/url/depart` 를 채우면 됩니다.
`config.example.yaml` 의 URL 과 선택자는 **자리표시자**입니다.

### 5.2 네이버 항공권 맞추기

네이버는 공개 API 가 없습니다. 이 어댑터는 웹 프론트가 쓰는 내부 엔드포인트를 부르며, 요청·응답 스키마를 모두 **설정으로** 교정합니다.

**요청 본문** — `flight_deal_alert/sources/naver_request_template.json`
브라우저에서 flight.naver.com 검색 → DevTools > Network > `searchFlights` 요청의 Payload 를 복사해 이 파일을 통째로 교체하고, 가변값만 플레이스홀더로 바꿉니다.
플레이스홀더: `{{origin}}` `{{destination}}` `{{departureDate}}`(yyyyMMdd) `{{departureDateDash}}` `{{returnDate}}` `{{returnDateDash}}` `{{adults}}` `{{cabin}}`(Y/C/F) `{{tripType}}`(OW/RT).
`_` 로 시작하는 키는 전송 전에 제거됩니다. `_return_leg` 는 왕복일 때 `fly` 배열에 붙습니다.

**응답 파싱** — 정해진 경로가 아니라 **필드 이름**으로 가격을 찾습니다. JSON 트리 전체를 훑으며 `price_keys` 중 하나를 가진 객체를 수집하고, 항공사·링크는 상위 노드에서 물려받습니다. 필드 위치가 바뀌어도 이름만 유지되면 동작하고, 이름까지 바뀌면 `config.yaml` 의 `sources.naver.price_keys` 에 추가하면 됩니다.

```bash
python -m flight_deal_alert diagnose naver
```
`가격으로 인식된 필드명: 없음` 이 나오면 응답 앞부분을 보고 `price_keys` 를 넓히세요.

**차단** — 네이버는 클라우드(AWS 등) IP 대역을 막습니다. 집 PC 나 홈서버에서 돌리는 걸 전제로 합니다.
이 저장소는 IP 우회·캡차 우회 같은 차단 회피 장치를 **넣지 않습니다.** 막히면 그 소스만 실패로 기록되고 나머지는 계속 돕니다.

## 6. 명령어

| 명령 | 설명 |
|---|---|
| `run [--dry-run] [--source X] [--force]` | 1회 실행. `--force` 는 주기 무시 |
| `daemon [--tick 60]` | 상주. 매 tick 마다 주기가 된 소스만 실행, 정해진 시각에 요약 전송 |
| `diagnose <source> [--route ICN-NRT]` | 실제로 보낸 요청과 받은 응답 원본, 파싱 결과. **꺼진 소스도 진단 가능** |
| `backtest [--days 30]` | 저장된 관측으로 판정만 재실행. 알림 안 보냄. 임계값 바꿨을 때 "과거였다면?" 확인용 |
| `report [--dry-run]` | 요약 리포트 (관측/알림 수, 소스별 성공·실패, 실행 기록 없는 소스) |

## 7. 구조

```
flight_deal_alert/
├── config.py        YAML + .env 로딩, 검증
├── models.py        Offer / Deal / SourceRun / Diagnostics
├── store.py         SQLite: offers(관측 이력) alerts(보낸 알림) source_runs(실행 기록)
├── detector.py      규칙 5개 + 긴급도 + 스팸 방지 + 조용한 시간대
├── pipeline.py      수집 → 판정 → 저장 → 알림 (판정이 저장보다 먼저)
├── scheduler.py     데몬
├── cli.py
├── notify/          stdout / telegram + 메시지 포맷
└── sources/         base / mock / travelpayouts / html_list / naver / skyscanner
```

설계 원칙:
- **판정은 저장보다 먼저.** 저장부터 하면 "역대 최저" 가 항상 참이 됩니다.
- **판정과 알림은 저장된 데이터만 읽습니다.** 그래서 `backtest` 와 과거 날짜 재조회가 가능합니다.
- **어댑터는 가져오기만.** 판정 로직을 어댑터에 섞지 않습니다.
- **소스 사이·요청 사이 지연.** 사이트당 요청 간격을 두고, 막히면 그냥 실패로 기록합니다.

## 8. 검증된 것 / 안 된 것

**검증됨 (테스트 72개)**: 판정 규칙 5개 각각의 경계값, 데이터 부족 시 상대 기준 미적용, 재알림 억제, 일일 한도, 배치 중복, 조용한 시간대 보류·재전송, 소스 실패 격리, 주기 판정, 백테스트, 요약 리포트, HTML 파싱(픽스처), 네이버 템플릿 렌더링·응답 추출, Travelpayouts 응답 파싱(문서 샘플), 설정 검증.

**검증 안 됨**: 실제 네이버·모두투어·땡처리 사이트 구조, Travelpayouts 실호출, Telegram 실전송. 전부 이 환경에서 외부 접근이 막혀 있어 확인하지 못했습니다. 각각 `diagnose` 와 `--dry-run` 으로 본인 환경에서 먼저 확인하세요.
