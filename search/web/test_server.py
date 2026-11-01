import unittest
from unittest.mock import patch
import server

class SearchTests(unittest.TestCase):
    def test_render_search_and_escaping(self):
        data = {"results": [{
            "url": "https://example.org/docs?x=1&y=2",
            "title": '<script>alert("hi")</script>',
            "content": "Open & useful",
            "engine": "example",
        }, {"url": "javascript:alert(1)", "title": "unsafe"}]}
        with patch.object(server, "get_results", return_value=data):
            body = server.render("学习 Python", 1, "general").decode()
        self.assertIn("学习 Python", body)
        self.assertIn("&lt;script&gt;", body)
        self.assertNotIn('<script>alert("hi")</script>', body)
        self.assertNotIn('href="javascript:', body)
        self.assertIn("Open &amp; useful", body)
        self.assertIn("下一页", body)

    def test_backend_failure_has_safe_message(self):
        with patch.object(server, "get_results", side_effect=TimeoutError("internal secret")):
            body = server.render("test", 1, "general").decode()
        self.assertIn("搜索服务暂时不可用", body)
        self.assertNotIn("internal secret", body)

if __name__ == "__main__":
    unittest.main()
