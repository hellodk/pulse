#!/usr/bin/env python3
"""
generate-diff-html.py  —  converts two text files into an inline HTML side-by-side diff table.
Outputs a self-contained <table> fragment (no <html>/<body>) suitable for embedding.

Usage:
    python3 generate-diff-html.py <old_file> <new_file> <output_html> [title] [fromdesc] [todesc]
"""

import difflib
import html
import sys
import os


def make_sidebyside(old_lines, new_lines, fromdesc="Live (deployed)", todesc="Incoming (new deploy)", context=5):
    """Build a side-by-side HTML diff table without using difflib.HtmlDiff (gives us full style control)."""

    matcher = difflib.SequenceMatcher(None, old_lines, new_lines, autojunk=False)
    rows = []
    lineno_old = 0
    lineno_new = 0

    def esc(s):
        return html.escape(s, quote=False)

    def row(left_no, left_line, right_no, right_line, kind):
        # kind: 'equal' | 'replace' | 'delete' | 'insert' | 'header'
        styles = {
            'equal':   ('', ''),
            'replace': ('background:#fff3cd;',  'background:#d4edda;'),
            'delete':  ('background:#f8d7da;',  'background:#f8f9fa;color:#adb5bd;'),
            'insert':  ('background:#f8f9fa;color:#adb5bd;', 'background:#d4edda;'),
            'header':  ('background:#e9ecef;font-weight:600;', 'background:#e9ecef;font-weight:600;'),
        }
        ls, rs = styles.get(kind, ('', ''))
        prefix = {'equal': '&nbsp;', 'replace': '~', 'delete': '−', 'insert': '+', 'header': ''}
        lp = prefix.get(kind, '&nbsp;')
        rp = {
            'equal': '&nbsp;', 'replace': '+', 'delete': '&nbsp;', 'insert': '+', 'header': ''
        }.get(kind, '&nbsp;')

        l_num = str(left_no)  if left_no  else ''
        r_num = str(right_no) if right_no else ''

        return (
            f'<tr>'
            f'<td class="ln">{l_num}</td>'
            f'<td class="pfx" style="{ls}">{lp}</td>'
            f'<td class="code" style="{ls}"><code>{esc(left_line)}</code></td>'
            f'<td class="sep"></td>'
            f'<td class="ln">{r_num}</td>'
            f'<td class="pfx" style="{rs}">{rp}</td>'
            f'<td class="code" style="{rs}"><code>{esc(right_line)}</code></td>'
            f'</tr>'
        )

    # header row
    rows.append(
        f'<tr>'
        f'<th colspan="3" style="background:#495057;color:#fff;padding:8px 12px;text-align:left;">'
        f'  {esc(fromdesc)}</th>'
        f'<th style="background:#343a40;width:4px;"></th>'
        f'<th colspan="3" style="background:#198754;color:#fff;padding:8px 12px;text-align:left;">'
        f'  {esc(todesc)}</th>'
        f'</tr>'
    )

    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == 'equal':
            # show a few context lines
            block = list(range(i1, i2))
            if len(block) > context * 2 + 1:
                for i in block[:context]:
                    lineno_old = i + 1
                    lineno_new = j1 + (i - i1) + 1
                    rows.append(row(lineno_old, old_lines[i], lineno_new, new_lines[j1 + (i - i1)], 'equal'))
                rows.append(
                    f'<tr><td colspan="7" style="text-align:center;background:#f8f9fa;'
                    f'color:#6c757d;font-size:11px;padding:4px;">… {len(block) - context*2} unchanged lines …</td></tr>'
                )
                for i in block[-context:]:
                    lineno_old = i + 1
                    lineno_new = j1 + (i - i1) + 1
                    rows.append(row(lineno_old, old_lines[i], lineno_new, new_lines[j1 + (i - i1)], 'equal'))
            else:
                for i in block:
                    lineno_old = i + 1
                    lineno_new = j1 + (i - i1) + 1
                    rows.append(row(lineno_old, old_lines[i], lineno_new, new_lines[j1 + (i - i1)], 'equal'))

        elif tag == 'replace':
            max_len = max(i2 - i1, j2 - j1)
            for k in range(max_len):
                lo = i1 + k if k < (i2 - i1) else None
                lno = old_lines[lo] if lo is not None else ''
                lnum = (lo + 1) if lo is not None else None
                ro = j1 + k if k < (j2 - j1) else None
                rno = new_lines[ro] if ro is not None else ''
                rnum = (ro + 1) if ro is not None else None
                rows.append(row(lnum, lno, rnum, rno, 'replace'))

        elif tag == 'delete':
            for i in range(i1, i2):
                rows.append(row(i + 1, old_lines[i], None, '', 'delete'))

        elif tag == 'insert':
            for j in range(j1, j2):
                rows.append(row(None, '', j + 1, new_lines[j], 'insert'))

    return '\n'.join(rows)


def main():
    if len(sys.argv) < 4:
        print(f"Usage: {sys.argv[0]} <old_file> <new_file> <output_html> [title] [fromdesc] [todesc]", file=sys.stderr)
        sys.exit(1)

    old_file  = sys.argv[1]
    new_file  = sys.argv[2]
    out_file  = sys.argv[3]
    title     = sys.argv[4] if len(sys.argv) > 4 else "Diff"
    fromdesc  = sys.argv[5] if len(sys.argv) > 5 else f"Live — {os.path.basename(old_file)}"
    todesc    = sys.argv[6] if len(sys.argv) > 6 else f"Incoming — {os.path.basename(new_file)}"

    def read(path):
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            return []
        with open(path) as f:
            return [l.rstrip('\n') for l in f.readlines()]

    old_lines = read(old_file)
    new_lines = read(new_file)

    if not old_lines and not new_lines:
        table = '<tr><td colspan="7" style="padding:16px;color:#6c757d;text-align:center;">No data available</td></tr>'
    elif not old_lines:
        table = make_sidebyside([], new_lines, fromdesc="(no live resource)", todesc=todesc)
    elif not new_lines:
        table = make_sidebyside(old_lines, [], fromdesc=fromdesc, todesc="(no incoming resource)")
    else:
        table = make_sidebyside(old_lines, new_lines, fromdesc=fromdesc, todesc=todesc)

    # Changed-line summary
    added   = old_lines.count  # placeholder
    changed = sum(1 for a, b in zip(old_lines, new_lines) if a != b)
    added   = max(0, len(new_lines) - len(old_lines))
    removed = max(0, len(old_lines) - len(new_lines))

    html_out = f"""<div class="diff-wrap">
  <div class="diff-title">{html.escape(title)}</div>
  <div class="diff-legend">
    <span class="leg leg-del">&#8722; Removed</span>
    <span class="leg leg-add">+ Added</span>
    <span class="leg leg-chg">~ Changed</span>
    <span style="color:#6c757d;font-size:11px;margin-left:8px;">{changed} changed &middot; {added} added &middot; {removed} removed</span>
  </div>
  <div style="overflow-x:auto;">
  <table class="diff-table">
    {table}
  </table>
  </div>
</div>"""

    os.makedirs(os.path.dirname(out_file) if os.path.dirname(out_file) else '.', exist_ok=True)
    with open(out_file, 'w') as f:
        f.write(html_out)
    print(f"Diff HTML written to {out_file} ({len(old_lines)} → {len(new_lines)} lines)")


if __name__ == '__main__':
    main()
