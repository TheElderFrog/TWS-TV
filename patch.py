"""Reproducible APK adaptation; preserves the original app's code and resource IDs."""
from pathlib import Path
import copy
import json
import shutil
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
NS = 'http://schemas.android.com/apk/res/android'
APP = 'http://schemas.android.com/apk/res-auto'
ET.register_namespace('android', NS)
ET.register_namespace('app', APP)
a = lambda n: '{' + NS + '}' + n
p = lambda n: '{' + APP + '}' + n

def write(path, element):
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(element, space='    ')
    ET.ElementTree(element).write(path, encoding='utf-8', xml_declaration=True)

# New strings are additive: original resource IDs and existing translations stay intact.
catalog = json.loads((Path(__file__).parent / 'locales/strings.json').read_text(encoding='utf-8'))
for qualifier, column in [('values', 1), ('values-zh', 0), ('values-zh-rCN', 0),
                           ('values-zh-rTW', 2), ('values-zh-rHK', 2), ('values-b+zh+Hant', 2), ('values-ja', 3)]:
    resources = ET.Element('resources')
    for key, translations in catalog.items():
        value = translations[column].replace('\\', '\\\\').replace('"', '\\"').replace("'", "\\'").replace('\n', '\\n')
        ET.SubElement(resources, 'string', {'name': key}).text = '"' + value + '"'
    write(root / 'res' / qualifier / 'tv_strings.xml', resources)
locales = ET.Element('locale-config')
for language in ['en', 'zh-Hans', 'zh-Hant', 'ja', 'cs', 'de', 'es', 'fr', 'it', 'ko', 'pl', 'pt-BR', 'ru', 'sv']:
    ET.SubElement(locales, 'locale', {a('name'): language})
write(root / 'res/xml/tv_locales.xml', locales)
arrays_path = root / 'res/values/arrays.xml'
arrays = ET.parse(arrays_path).getroot()
for name, first, extra in [('languages', '@string/tv_system_language', ['繁體中文', '日本語']),
                            ('languages_values', '', ['zh-TW', 'ja'])]:
    array = next(el for el in arrays if el.get('name') == name)
    item = ET.Element('item'); item.text = first; array.insert(0, item)
    for text in extra:
        ET.SubElement(array, 'item').text = text
write(arrays_path, arrays)
preferences_path = root / 'res/xml/preferences_general.xml'
preferences = ET.parse(preferences_path).getroot()
language = next(el for el in preferences.iter() if el.get(a('key')) == 'general_language')
language.set(a('defaultValue'), '')
language.set(p('defaultValue'), '')
write(preferences_path, preferences)

# Change only the runtime package string, not the original Java/resource namespace.
for folder in ['smali', 'smali_classes2', 'res']:
    for path in (root / folder).rglob('*'):
        if path.suffix in ('.smali', '.xml'):
            text = path.read_text(encoding='utf-8')
            changed = text.replace('se.zepiwolf.tws.web', 'se.zepiwolf.tws.tv')
            if changed != text:
                path.write_text(changed, encoding='utf-8')

manifest_path = root / 'AndroidManifest.xml'
manifest = ET.parse(manifest_path).getroot()
manifest.set('package', 'se.zepiwolf.tws.tv')
for el in manifest.iter():
    for key, val in list(el.attrib.items()):
        el.set(key, val.replace('se.zepiwolf.tws.web', 'se.zepiwolf.tws.tv'))
for feature in ['android.hardware.touchscreen', 'android.hardware.faketouch', 'android.software.leanback']:
    ET.SubElement(manifest, 'uses-feature', {a('name'): feature, a('required'): 'false'})
app = manifest.find('application')
app.set(a('name'), 'se.zepiwolf.tws.tv.TvApplication')
app.set(a('banner'), '@drawable/tv_banner')
app.set(a('extractNativeLibs'), 'true')
app.set(a('label'), "The Wolf's Stash TV")
app.set(a('localeConfig'), '@xml/tv_locales')
# Local phone-login page works offline; credentials are encrypted before submission.
assets = root / 'assets'
assets.mkdir(exist_ok=True)
shutil.copyfile(Path(__file__).parent / 'tools/jsencrypt-3.3.2.min.js', assets / 'tv-login-crypto.js')
shutil.copyfile(Path(__file__).parent / 'assets/tv-login.html', assets / 'tv-login.html')
for el in app.findall('activity'):
    if el.get(a('name'), '').startswith('se.zepiwolf.tws.'):
        el.set(a('screenOrientation'), 'landscape')
        el.set(a('windowSoftInputMode'), 'stateAlwaysHidden|adjustResize')
regular = next(el for el in app.findall('activity-alias') if el.get(a('name')) == 'se.zepiwolf.tws.REGULAR')
regular.set(a('banner'), '@drawable/tv_banner')
ET.SubElement(regular.find('intent-filter'), 'category', {a('name'): 'android.intent.category.LEANBACK_LAUNCHER'})
write(manifest_path, manifest)

# Keep the original ID for code lookups, but never reserve space for an empty banner.
banner_path = root / 'res/layout/banner_ad.xml'
banner = ET.parse(banner_path).getroot()
banner.set(a('layout_height'), '0dp')
banner.set(a('minHeight'), '0dp')
banner.set(a('visibility'), 'gone')
write(banner_path, banner)

# Dispatch patch sits before the original AppCompat dispatch implementation.
base = root / 'smali' / 'm8.smali'
text = base.read_text(encoding='utf-8')
needle = '.method public final dispatchKeyEvent(Landroid/view/KeyEvent;)Z\n    .locals 3\n'
assert text.count(needle) == 1, 'Unexpected APK: AppCompat dispatch changed'
hook = '''
    invoke-static {p0, p1}, Lse/zepiwolf/tws/tv/TvSupport;->dispatch(Landroid/app/Activity;Landroid/view/KeyEvent;)Z
    move-result v0
    if-eqz v0, :tv_original_dispatch
    const/4 v0, 0x1
    return v0
    :tv_original_dispatch
'''
base.write_text(text.replace(needle, needle + hook), encoding='utf-8')

# Viewer bridge intentionally targets this APK, rather than guessing shrunk Media3 names.
player = (root / 'smali/u11.smali').read_text(encoding='utf-8')
controller = (root / 'smali/zt2.smali').read_text(encoding='utf-8')
assert '.method public final m()J' in player and '.method public final r()J' in player, 'Player getters changed'
assert '.method public final g()V' in controller, 'Controller hide method changed'
search_view = (root / 'smali/androidx/appcompat/widget/SearchView.smali').read_text(encoding='utf-8')
assert '.method public final r(Ljava/lang/CharSequence;)V' in search_view, 'Search query setter changed'
assert '.method public static j(Le32;)V' in (root / 'smali/x8.smali').read_text(encoding='utf-8'), 'App locale setter changed'
assert '.method public static a(Ljava/lang/String;)Le32;' in (root / 'smali/e32.smali').read_text(encoding='utf-8'), 'Locale list factory changed'

# Home uses the existing toolbar, pager and bottom actions, plus a native side rail.
home = ET.parse(root / 'res/layout/activity_main.xml').getroot()
home.set(a('layout_marginStart'), '168dp')
home.set(a('layout_marginEnd'), '24dp')
home.set(a('layout_marginTop'), '16dp')
home.set(a('layout_marginBottom'), '36dp')
for el in home.iter():
    if el.get(a('id')) == '@id/toolbar':
        el.attrib.pop(p('layout_scrollFlags'), None)
    if el.get(a('id')) == '@id/bottom_nav':
        el.attrib.pop(p('layout_behavior'), None)
    if el.get(a('id')) == '@id/lLText':
        el.set(a('paddingBottom'), '64dp')
        el.set(a('clipToPadding'), 'true')
frame = ET.Element('FrameLayout', {a('layout_width'): 'match_parent', a('layout_height'): 'match_parent'})
frame.append(home)
write(root / 'res/layout-land/activity_main.xml', frame)

# Two panes retain the exact original view IDs and original action bar.
post = ET.parse(root / 'res/layout/adapter_post_item.xml').getroot()
scroll = next(el for el in post if el.tag == 'ScrollView' and el.get(a('id')) == '@id/scrollView')
stack = scroll[0]
media = stack[1]
assert media.tag == 'FrameLayout'
stack.remove(media)
fade = stack[0]
stack.remove(fade)
scroll.set(a('layout_width'), '0dp')
scroll.set(a('layout_height'), 'match_parent')
scroll.set(a('layout_weight'), '1')
scroll.set(a('paddingStart'), '16dp')
scroll.set(a('paddingEnd'), '16dp')
scroll.set(a('paddingTop'), '12dp')
scroll.set(a('clipToPadding'), 'false')
scroll.set(a('scrollbars'), 'vertical')
stack.set(a('paddingBottom'), '96dp')
pane = ET.Element('FrameLayout', {a('id'): '@+id/tv_media_pane', a('layout_width'): '0dp',
    a('layout_height'): 'match_parent', a('layout_weight'): '2', a('background'): '#080D13'})
media.set(a('layout_height'), 'match_parent')
pane.append(media)
fade.set(a('visibility'), 'gone')
pane.append(fade)
row = ET.Element('LinearLayout', {a('layout_width'): 'match_parent', a('layout_height'): 'match_parent',
    a('orientation'): 'horizontal', a('paddingStart'): '24dp', a('paddingEnd'): '24dp',
    a('paddingTop'): '16dp', a('paddingBottom'): '68dp',
    p('layout_constraintTop_toTopOf'): 'parent', p('layout_constraintBottom_toBottomOf'): 'parent'})
post.remove(scroll)
row.append(pane)
row.append(scroll)
post.insert(0, row)
for el in post:
    if el.get(a('id')) in ('@id/imgMini', '@id/fLGoLeft1', '@id/fLGoLeft2', '@id/fLGoRight1', '@id/fLGoRight2'):
        el.set(a('visibility'), 'gone')
buttons = next(el for el in post if el.get(a('id')) == '@id/lLButtons')
buttons.set(a('layout_height'), '56dp')
buttons.set(a('layout_marginStart'), '24dp')
buttons.set(a('layout_marginEnd'), '24dp')
buttons.set(a('layout_marginBottom'), '8dp')
buttons.set(a('alpha'), '1.0')
write(root / 'res/layout-land/adapter_post_item.xml', post)

# Larger spacing and readable grid labels for a living-room screen.
dimens = ET.Element('resources')
for key, val in [('grid_item_margin', '5dp'), ('grid_item_info_text_size', '14sp'),
                 ('card_corner_radius', '8dp'), ('main_btn_width', '56dp'),
                 ('post_content_tags_title_size', '18sp')]:
    el = ET.SubElement(dimens, 'dimen', {'name': key}); el.text = val
write(root / 'res/values-land/dimens.xml', dimens)

# Grid images must be focusable before the first RecyclerView focus search.
grid_path = root / 'res/layout/adapter_grid_item.xml'
grid = ET.parse(grid_path).getroot()
for el in grid.iter():
    if el.get(a('id')) == '@id/imgPreview':
        el.set(a('focusable'), 'true'); el.set(a('focusableInTouchMode'), 'true')
    if el.get(a('id')) == '@id/imgInfoBtn':
        el.set(a('focusable'), 'false')
write(grid_path, grid)

yaml = root / 'apktool.yml'
text = yaml.read_text(encoding='utf-8')
text = text.replace("versionCode: '172'", "versionCode: '177'").replace('versionCode: 172', 'versionCode: 177')
text = text.replace('versionName: beta-4.16.4', 'versionName: beta-4.16.4-tv5')
yaml.write_text(text, encoding='utf-8')
print('Manifest, remote dispatch, home rail and two-pane viewer patched.')
