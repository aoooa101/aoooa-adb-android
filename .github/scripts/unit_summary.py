"""Summarize the current Gradle JVM test task's JUnit XML files."""
from pathlib import Path
import os
import xml.etree.ElementTree as ET


def summary(directory):
    files = sorted(directory.glob('TEST-*.xml'))
    if not files:
        return '未取得单元测试结果（没有 JUnit XML）。'
    total = passed = failed = errors = skipped = 0
    for path in files:
        suite = ET.parse(path).getroot()
        if suite.tag != 'testsuite':
            raise ValueError('Invalid Gradle JUnit report')
        count, failures, errs, skips = (int(suite.attrib[k]) for k in ('tests', 'failures', 'errors', 'skipped'))
        ok = count - failures - errs - skips
        if min(ok, failures, errs, skips) < 0:
            raise ValueError('Inconsistent Gradle JUnit counts')
        total += count
        passed += ok
        failed += failures
        errors += errs
        skipped += skips
    return (f'| 总数 | 通过 | 失败 | 错误 | 跳过 |\n|---:|---:|---:|---:|---:|\n'
            f'| {total} | {passed} | {failed} | {errors} | {skipped} |')


if __name__ == '__main__':
    try:
        text = summary(Path('app/build/test-results/testDebugUnitTest'))
    except (ET.ParseError, KeyError, OSError, ValueError):
        text = '未取得完整单元测试结果（报告无法读取或计数不一致）。'
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as stream:
        print('### JVM 单元测试\n\n' + text, file=stream)
