from pathlib import Path
import sys
from PIL import Image, ImageDraw, ImageFont

out = Path(sys.argv[1])
out.parent.mkdir(parents=True, exist_ok=True)
im = Image.new('RGB', (320, 180), '#0d151e')
d = ImageDraw.Draw(im)
d.rounded_rectangle((12, 12, 308, 168), 18, fill='#172a39', outline='#73e3ff', width=3)
font = ImageFont.truetype('C:/Windows/Fonts/segoeuib.ttf', 54)
small = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 21)
d.text((36, 34), 'TWS', font=font, fill='white')
d.rounded_rectangle((205, 45, 286, 100), 10, fill='#73e3ff')
d.text((219, 50), 'TV', font=ImageFont.truetype('C:/Windows/Fonts/segoeuib.ttf', 35), fill='#0d151e')
d.text((35, 120), "The Wolf's Stash", font=small, fill='#9eb7c8')
im.save(out)
