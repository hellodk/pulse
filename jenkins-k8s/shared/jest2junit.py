#!/usr/bin/env python3
"""Convert jest --json output to JUnit XML for Jenkins Test Result Trend."""
import json, sys
from xml.etree.ElementTree import Element, SubElement, ElementTree

def convert(src, dst):
    with open(src) as f:
        data = json.load(f)
    root = Element('testsuites')
    for suite in data.get('testResults', []):
        name = suite.get('testFilePath', '').split('/')[-1]
        results = suite.get('testResults', [])
        ts = SubElement(root, 'testsuite', name=name, tests=str(len(results)))
        for t in results:
            parts = t.get('ancestorTitles', []) + [t.get('title', '')]
            title = ' > '.join(parts)
            tc = SubElement(ts, 'testcase', name=title, classname=name,
                            time=str(round((t.get('duration') or 0) / 1000.0, 3)))
            if t.get('status') == 'failed':
                msgs = t.get('failureMessages', [])
                text = '\n'.join(msgs)[:500].replace('&', '&amp;').replace('<', '&lt;')
                SubElement(tc, 'failure').text = text
    ElementTree(root).write(dst, xml_declaration=True, encoding='utf-8')
    print(f"jest2junit: {len(data.get('testResults', []))} suites → {dst}")

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("Usage: jest2junit.py <input.json> <output.xml>")
        sys.exit(1)
    try:
        convert(sys.argv[1], sys.argv[2])
    except Exception as e:
        print(f"jest2junit: skipped — {e}")
