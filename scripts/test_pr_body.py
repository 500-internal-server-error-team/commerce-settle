import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pr_body


class PrBodyTest(unittest.TestCase):
    def test_compact_results_and_unfinished_checks_are_allowed(self):
        pr_body.validate("## 검증\n\n- [x] build 259개 통과\n- [ ] 부하 시험 미실행: 범위 밖\n\n## 연결 이슈\n")

    def test_actual_loose_results_plus_checklist_are_rejected(self):
        body = "## 검증\n\n- build 259개 통과\n- CI 통과\n\n- [x] build 확인\n- [x] CI 확인\n"
        with self.assertRaises(ValueError):
            pr_body.validate(body)

    def test_blank_lines_or_paragraphs_between_items_are_rejected(self):
        for middle in ["", "추가 설명", "- 별도 결과"]:
            with self.subTest(middle=middle), self.assertRaises(ValueError):
                pr_body.validate(f"## 검증\n\n- [x] 첫 검사\n{middle}\n- [x] 다음 검사\n")

    def test_other_sections_and_fenced_examples_do_not_change_target(self):
        pr_body.validate(
            "## 변경 이유\n\n```markdown\n## 검증\n\n- 잘못된 예시\n```\n\n"
            "## 검증\n\n<!-- 결과는 항목에 기록 -->\n- [x] 검사 통과\n\n"
            "## 관련 문서\n\n- 첫 문서\n\n- 다른 문서\n"
        )

    def test_comment_headings_do_not_hide_loose_list_tail(self):
        body = (
            "## 검증\n- [x] 첫 검사 <!--\n## 관련 문서\n-->\n"
            "- [x] 다음 검사\n\n- [x] 마지막 검사\n"
        )
        with self.assertRaises(ValueError):
            pr_body.validate(body)

    def test_comment_headings_do_not_reject_compact_list(self):
        pr_body.validate(
            "<!--\n## 검증\n-->\n## 검증\n"
            "- [x] 첫 검사 <!--\n## 관련 문서\n-->\n- [x] 다음 검사\n"
        )

    def test_literal_comments_in_code_do_not_hide_real_section(self):
        pr_body.validate(
            "## 변경 이유\n\n````html\n<!--\n```\n````\n\n"
            "## 검증\n\n- [x] `<!--` 문자열 검사 통과\n"
        )

    def test_missing_duplicate_or_indented_verification_lists_are_rejected(self):
        for body in ["## 변경 이유\n설명", "## 검증\n- [x] 통과\n## 검증\n- [x] 통과", "## 검증\n\n    - [x] 통과"]:
            with self.subTest(body=body), self.assertRaises(ValueError):
                pr_body.validate(body)

    @patch("pr_body.subprocess.run")
    def test_invalid_body_is_stopped_before_any_github_call(self, run):
        source = Mock(spec=Path)
        source.read_text.return_value = "## 검증\n\n- 결과\n\n- [x] 중복 확인"
        with self.assertRaises(ValueError):
            pr_body.publish("owner/repo", 60, "owner", source)
        run.assert_not_called()

    @patch("pr_body.subprocess.run")
    def test_wrong_account_is_stopped_before_publication(self, run):
        source = Mock(spec=Path)
        source.read_text.return_value = "## 검증\n\n- [x] 통과"
        run.return_value = SimpleNamespace(stdout="different\n")
        with self.assertRaises(ValueError):
            pr_body.publish("owner/repo", 60, "owner", source)
        self.assertEqual(run.call_count, 1)
        self.assertEqual(run.call_args.args[0], ["gh", "api", "user", "--jq", ".login"])

    @patch("pr_body.subprocess.run")
    def test_valid_body_is_published_as_file_to_explicit_target(self, run):
        source = Mock(spec=Path)
        source.read_text.return_value = "## 검증\n\n- [x] `build`와 $(문자열) 검사 통과"
        run.return_value = SimpleNamespace(stdout="owner\n")
        pr_body.publish("owner/repo", 60, "owner", source, "제목")
        self.assertEqual(run.call_count, 2)
        self.assertEqual(
            run.call_args.args[0],
            ["gh", "pr", "edit", "60", "--repo", "owner/repo", "--body-file", str(source), "--title", "제목"],
        )
        self.assertNotIn("shell", run.call_args.kwargs)

    @patch("pr_body.subprocess.run")
    def test_invalid_new_pr_is_stopped_before_any_github_call(self, run):
        source = Mock(spec=Path)
        source.read_text.return_value = "## 검증\n\n- 결과\n\n- [x] 중복 확인"
        with self.assertRaises(ValueError):
            pr_body.create("owner/repo", "develop", "feature", "제목", "owner", source)
        run.assert_not_called()

    @patch("pr_body.subprocess.run")
    def test_new_pr_requires_explicit_base_and_head_and_preserves_body_file(self, run):
        source = Mock(spec=Path)
        source.read_text.return_value = "## 검증\n\n- [x] 통과"
        run.return_value = SimpleNamespace(stdout="owner\n")
        with self.assertRaises(ValueError):
            pr_body.create("owner/repo", "", "feature", "제목", "owner", source)
        run.assert_not_called()
        pr_body.create("owner/repo", "develop", "feature", "제목", "owner", source)
        self.assertEqual(
            run.call_args.args[0],
            ["gh", "pr", "create", "--repo", "owner/repo", "--base", "develop", "--head", "feature",
             "--title", "제목", "--body-file", str(source)],
        )
        self.assertNotIn("shell", run.call_args.kwargs)


if __name__ == "__main__":
    unittest.main()
