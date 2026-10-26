# ibm_small — 통합 테스트·CI용 축소본

지금 들어 있는 것은 **형식만 같은 합성 샘플**(1,551행)이다. 실제 IBM HI-Small 원본으로 만든 축소본(약 1만 행)은
진희 PC 에서 `tools/fixture/make_fixture.py` 로 다시 만들어 이 폴더를 덮어쓴다 (W1 마지막 작업).

- `HI-Small_Trans.csv`, `HI-Small_Patterns.txt` — 원본과 같은 형식
- `expected.json` — 정답지. 행 수·계좌 수·금액 합·날짜별 건수. `alerts` 는 10/27 룰 확정 후 준영이 `draft_alerts.py` 로 채운다
- 명세: `tools/fixture/fixture_spec.md`
