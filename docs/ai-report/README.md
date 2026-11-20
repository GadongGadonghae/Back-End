# AI 생산 리포트 (report 패키지)

계획수정 보고서 화면(프론트 `wireframes/후보/B-report.html`)이 쓰는 AI 보고서 생성 API입니다.

| 항목 | 내용 |
|---|---|
| API | `POST /api/ai-reports/generate` (로그인 필요, JWT) |
| 코드 | `src/main/java/com/gadong/sfaas/report/` |
| 설정 | `application.yml` 의 `ai.report.*` (기본 시연 모드: API 키 없이 템플릿 문장) |
| 테스트 | `AiReportServiceTest`(계산·추가 요청 해석·LLM 실패 대체), `AiReportApiTest`(201·400·401) |

- 부족 수량·라인별 재배정은 Java에서 계산하고, LLM은 보고서 문장만 씁니다.
- LLM 호출이 실패하면(키 없음·시간 초과·응답 형식 오류) 템플릿 문장으로 대체하고 `fallbackReason`에 이유를 담습니다.
- 오류 응답은 팀 공통 `global/exception` 형식을 그대로 씁니다.

## 지금 단계

1단계(기준 맞추기)까지 반영했습니다. 지금은 화면이 수치(`lineConditions` 등)를 만들어 보내고, 백엔드는 계산·문장 생성만 합니다.
2단계에서 `downtime_event`·`reassignment_report`·`production_target` 등을 조회해 백엔드가 직접 스냅샷을 만들도록 바꿉니다.
자세한 내용과 팀 확인 사항은 [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md) 를 보세요.

> 문서 안 API 경로는 팀 백엔드 기준(`/api`, v1 없음)으로 맞췄습니다.
