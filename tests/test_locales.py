"""Check complete translations and resource keys without requiring a TV."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = json.loads((ROOT / 'locales/strings.json').read_text(encoding='utf-8'))

class LocalesTest(unittest.TestCase):
    def test_complete_translations(self):
        for key, values in CATALOG.items():
            self.assertRegex(key, r'^tv_[a-z_]+$')
            self.assertEqual(len(values), 4, key)
            placeholders = re.findall(r'%\d+\$[ds]', values[0])
            for text in values:
                self.assertTrue(text.strip(), key)
                self.assertEqual(re.findall(r'%\d+\$[ds]', text), placeholders, key)

    def test_all_referenced_keys_exist(self):
        for file in (ROOT / 'src').rglob('*.java'):
            text = file.read_text(encoding='utf-8')
            for key in re.findall(r'"(tv_[a-z_]+)"', text):
                if key in {'tv_media_pane', 'tv_defaults_v1', 'tv_system_language_v1'}:
                    continue
                self.assertIn(key, CATALOG, f'{file.name}: {key}')
            self.assertIsNone(re.search(r'[\u4e00-\u9fff]', text), file.name)

    def test_phone_keys_exist(self):
        page = (ROOT / 'assets/tv-login.html').read_text(encoding='utf-8')
        for key in re.findall(r'tv_[a-z_]+', page):
            self.assertIn(key, CATALOG)
        self.assertNotIn('lang=zh-CN', page)

    def test_no_forced_language(self):
        text = (ROOT / 'src/se/zepiwolf/tws/tv/TvApplication.java').read_text(encoding='utf-8')
        self.assertNotIn('"zh-CN"', text)
        self.assertIn('getMethod("a", String.class).invoke(null, "")', text)
        patch = (ROOT / 'patch.py').read_text(encoding='utf-8')
        self.assertIn("language.set(a('defaultValue'), '')", patch)
        self.assertIn("language.set(p('defaultValue'), '')", patch)

if __name__ == '__main__':
    unittest.main()
