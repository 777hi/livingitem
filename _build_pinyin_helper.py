import os
import re

fragment_path = os.path.join(os.path.dirname(__file__), '_pinyin_data_fragment.txt')
java_path = os.path.join(os.path.dirname(__file__), 'src', 'main', 'java', 'com', 'qiqi', 'li', 'client', 'util', 'PinyinHelper.java')

with open(fragment_path, 'r', encoding='utf-8') as f:
    fragment = f.read()

chars_match = re.search(r'private static final String CHARS =\s*\n(.*?);', fragment, re.DOTALL)
if not chars_match:
    print('ERROR: Could not find CHARS in fragment')
    exit(1)

pinyins_match = re.search(r'private static final String\[\] PINYINS = \{\s*\n(.*?)\n\};', fragment, re.DOTALL)
if not pinyins_match:
    print('ERROR: Could not find PINYINS in fragment')
    exit(1)

chars_raw = chars_match.group(1)
chars_raw = chars_raw.replace('"', '').replace('+', '').replace('\n', '').replace(' ', '').replace('\r', '')
print(f'CHARS length: {len(chars_raw)}')

pinyins_raw = pinyins_match.group(1)
pinyin_items = [p.strip().strip('"') for p in pinyins_raw.split(',') if p.strip()]
print(f'PINYINS count: {len(pinyin_items)}')

if len(chars_raw) != len(pinyin_items):
    print(f'WARNING: CHARS length ({len(chars_raw)}) != PINYINS count ({len(pinyin_items)})')

chars_per_line = 80
chars_lines = []
for i in range(0, len(chars_raw), chars_per_line):
    chunk = chars_raw[i:i+chars_per_line]
    if i + chars_per_line < len(chars_raw):
        chars_lines.append(f'        "{chunk}" +')
    else:
        chars_lines.append(f'        "{chunk}";')

pinyin_str = ','.join(pinyin_items)
max_part_bytes = 60000
pinyin_parts = []
start = 0
while start < len(pinyin_str):
    end = start + max_part_bytes
    if end < len(pinyin_str):
        last_comma = pinyin_str.rfind(',', start, end)
        if last_comma > start:
            end = last_comma + 1
    pinyin_parts.append(pinyin_str[start:end])
    start = end

print(f'PINYIN_DATA split into {len(pinyin_parts)} parts')
for i, part in enumerate(pinyin_parts):
    print(f'  Part {i}: {len(part)} chars')

pinyin_per_line = 4000
pinyin_part_fields = []
for part_idx, part in enumerate(pinyin_parts):
    lines = []
    for i in range(0, len(part), pinyin_per_line):
        chunk = part[i:i+pinyin_per_line]
        if i + pinyin_per_line < len(part):
            lines.append(f'        "{chunk}" +')
        else:
            lines.append(f'        "{chunk}";')
    pinyin_part_fields.append((part_idx, lines))

pinyin_fields_code = []
for part_idx, lines in pinyin_part_fields:
    field_name = f'PINYIN_DATA_{part_idx}'
    pinyin_fields_code.append(f'    private static final String {field_name} =')
    for line in lines:
        pinyin_fields_code.append(line)

pinyin_concat_expr = f'PINYIN_DATA_0'
for i in range(1, len(pinyin_parts)):
    pinyin_concat_expr += f'.concat(PINYIN_DATA_{i})'

java_code = f'''package com.qiqi.li.client.util;

import java.util.Locale;

public class PinyinHelper {{

    private static final String CHARS =
{chr(10).join(chars_lines)}

{chr(10).join(pinyin_fields_code)}

    private static final String[] PINYINS = {pinyin_concat_expr}.split(",");

    private static int binarySearch(char c) {{
        int low = 0;
        int high = CHARS.length() - 1;
        while (low <= high) {{
            int mid = (low + high) >>> 1;
            char midVal = CHARS.charAt(mid);
            if (midVal < c) {{
                low = mid + 1;
            }} else if (midVal > c) {{
                high = mid - 1;
            }} else {{
                return mid;
            }}
        }}
        return -1;
    }}

    public static String getPinyin(char c) {{
        int idx = binarySearch(c);
        return idx >= 0 ? PINYINS[idx] : null;
    }}

    public static String toPinyin(String text) {{
        if (text == null || text.isEmpty()) {{
            return "";
        }}
        StringBuilder result = new StringBuilder();
        for (char ch : text.toCharArray()) {{
            String py = getPinyin(ch);
            if (py != null) {{
                result.append(py);
            }} else if (isLatinOrDigit(ch)) {{
                result.append(Character.toLowerCase(ch));
            }}
        }}
        return result.toString();
    }}

    public static String toPinyinInitials(String text) {{
        if (text == null || text.isEmpty()) {{
            return "";
        }}
        StringBuilder result = new StringBuilder();
        for (char ch : text.toCharArray()) {{
            String py = getPinyin(ch);
            if (py != null && !py.isEmpty()) {{
                result.append(py.charAt(0));
            }} else if (isLatinOrDigit(ch)) {{
                result.append(Character.toLowerCase(ch));
            }}
        }}
        return result.toString();
    }}

    public static boolean isPinyinMatch(String text, String searchText) {{
        if (text == null || searchText == null) {{
            return false;
        }}
        if (searchText.isEmpty()) {{
            return true;
        }}
        String lowerSearch = searchText.toLowerCase(Locale.ROOT);
        String lowerText = text.toLowerCase(Locale.ROOT);
        if (lowerText.contains(lowerSearch)) {{
            return true;
        }}
        String fullPinyin = toPinyin(text);
        if (fullPinyin.contains(lowerSearch)) {{
            return true;
        }}
        String initials = toPinyinInitials(text);
        if (initials.contains(lowerSearch)) {{
            return true;
        }}
        return hybridMatch(text, lowerSearch);
    }}

    private static boolean hybridMatch(String text, String search) {{
        int splitIndex = findSplitIndex(search);
        if (splitIndex > 0 && splitIndex < search.length()) {{
            String part1 = search.substring(0, splitIndex);
            String part2 = search.substring(splitIndex);
            String lowerText = text.toLowerCase(Locale.ROOT);
            String fullPinyin = toPinyin(text);
            String initials = toPinyinInitials(text);
            boolean part1Match = lowerText.contains(part1)
                || fullPinyin.contains(part1)
                || initials.contains(part1);
            boolean part2Match = lowerText.contains(part2)
                || fullPinyin.contains(part2)
                || initials.contains(part2);
            return part1Match && part2Match;
        }}
        return false;
    }}

    private static int findSplitIndex(String search) {{
        for (int i = 0; i < search.length(); i++) {{
            char ch = search.charAt(i);
            boolean isLatin = isLatinOrDigit(ch);
            if (i > 0) {{
                char prev = search.charAt(i - 1);
                boolean prevIsLatin = isLatinOrDigit(prev);
                if (isLatin != prevIsLatin) {{
                    return i;
                }}
            }}
        }}
        return -1;
    }}

    private static boolean isLatinOrDigit(char ch) {{
        return (ch >= 'a' && ch <= 'z') ||
               (ch >= 'A' && ch <= 'Z') ||
               (ch >= '0' && ch <= '9');
    }}

    public static int getDictionarySize() {{
        return CHARS.length();
    }}
}}
'''

with open(java_path, 'w', encoding='utf-8') as f:
    f.write(java_code)

print(f'Written {len(java_code)} chars to PinyinHelper.java')