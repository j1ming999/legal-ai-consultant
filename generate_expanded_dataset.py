import json, re, zipfile, html
from pathlib import Path
from collections import Counter, defaultdict

root = Path('E:/ideaproject/consultant3')
content_dir = root / 'src/main/resources/content'
out = root / 'src/main/resources/test-dataset.json'
backup = root / 'src/main/resources/test-dataset-200-backup.json'
if not backup.exists() and out.exists():
    backup.write_text(out.read_text(encoding='utf-8'), encoding='utf-8')

cn_nums = '一二三四五六七八九十百千零○〇0-9'
article_re = re.compile(r'^[　\s]*(第[' + cn_nums + r']+条[之的]?[' + cn_nums + r']*)[　\s]*(.*)')
noise = ['中华人民共和国', '最高人民法院', '浙江省', '杭州市', '关于', '适用', '若干问题', '解释', '规定', '办法', '条例', '修正', '修订', '实施', '暂行']

domain_alias = {
    '民法典': ['合同纠纷', '侵权责任', '婚姻家庭', '继承纠纷'],
    '消费者权益保护法': ['七天无理由退货', '假货赔偿', '虚假宣传'],
    '产品质量法': ['产品缺陷', '产品质量责任', '生产者义务'],
    '道路交通安全法': ['交通事故赔偿', '酒驾处罚', '道路通行规则'],
    '社会保险法': ['养老保险', '医疗保险', '社保缴费'],
    '工伤保险条例': ['工伤认定', '停工留薪期', '工伤待遇'],
    '劳动': ['劳动争议', '工资拖欠', '加班工资'],
    '物业': ['物业收费', '业主权利', '小区共有部分'],
    '医疗': ['医疗事故', '医疗纠纷', '患者赔偿'],
    '公司法': ['股东知情权', '股权转让', '公司债务'],
    '保险法': ['保险理赔', '如实告知义务', '保险合同'],
    '行政处罚法': ['行政处罚种类', '不予处罚', '听证程序'],
    '土地': ['土地征收', '宅基地', '土地承包经营权'],
    '个人所得税法': ['个税税率', '专项附加扣除', '纳税申报'],
    '反家庭暴力法': ['家庭暴力', '人身安全保护令', '家暴告诫'],
    '妇女权益保障法': ['就业性别歧视', '妇女权益保护', '生育权益'],
    '知识产权': ['知识产权保护', '专利纠纷', '商标侵权'],
    '安全生产': ['安全生产责任', '事故报告', '应急处置'],
    '村民委员会': ['村委会选举', '村民自治', '村民会议'],
    '道路运输': ['道路运输经营许可', '运输安全', '客运管理'],
}

def read_text(path):
    if path.suffix.lower() == '.txt':
        for enc in ['utf-8', 'gb18030', 'gbk']:
            try:
                return path.read_text(encoding=enc)
            except UnicodeDecodeError:
                continue
        return path.read_text(encoding='utf-8', errors='ignore')
    if path.suffix.lower() == '.docx':
        try:
            with zipfile.ZipFile(path) as z:
                xml = z.read('word/document.xml').decode('utf-8', errors='ignore')
            xml = re.sub(r'</w:p>', '\n', xml)
            xml = re.sub(r'<[^>]+>', '', xml)
            return html.unescape(xml)
        except Exception:
            return ''
    return ''

def law_name(path):
    return path.stem.replace('《', '').replace('》', '').replace('〈', '').replace('〉', '')

def split_articles(text):
    result = []
    current_num = None
    current_lines = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        m = article_re.match(line)
        if m:
            if current_num and current_lines:
                result.append((current_num, '\n'.join(current_lines)))
            current_num = m.group(1)
            current_lines = [line]
        elif current_num:
            current_lines.append(line)
    if current_num and current_lines:
        result.append((current_num, '\n'.join(current_lines)))
    return result

def domain_terms(law):
    terms = []
    for key, values in domain_alias.items():
        if key in law:
            terms.extend(values)
    if terms:
        return terms
    base = law
    for n in noise:
        base = base.replace(n, '')
    base = re.sub(r'[（(].*?[）)]', '', base).strip(' ，、')
    if not base:
        base = '相关事项'
    return [base + '的权利义务', base + '的法律责任', base + '的办理程序']

def article_terms(article_text, law):
    text = re.sub(r'第[' + cn_nums + r']+条[之的]?[' + cn_nums + r']*', '', article_text)
    candidates = []
    for w in re.findall(r'[一-龥]{2,8}', text):
        if any(n in w for n in noise):
            continue
        if w in ['应当', '可以', '不得', '依照', '有关', '进行', '或者', '以及', '其他', '具有', '下列']:
            continue
        if w not in candidates:
            candidates.append(w)
        if len(candidates) >= 2:
            break
    if candidates:
        return candidates
    return domain_terms(law)[:2]

files = sorted([p for p in content_dir.iterdir() if p.suffix.lower() in ['.txt', '.docx']])
laws = []
for p in files:
    law = law_name(p)
    articles = split_articles(read_text(p))
    if articles:
        laws.append({'law': law, 'articles': articles[:60], 'path': str(p)})

# Put common civil-consultation laws first but still sample round-robin.
priority = ['民法典','消费者权益保护法','产品质量法','道路交通安全法','社会保险法','劳动','工伤保险条例','物业','医疗','公司法','保险法','行政处罚法','土地','个人所得税法','反家庭暴力法','妇女权益保障法','知识产权','安全生产','村民委员会','道路运输']
def law_priority(item):
    for i, key in enumerate(priority):
        if key in item['law']:
            return i
    return 99
laws.sort(key=lambda x: (law_priority(x), x['law']))

used = set()
data = []
def add(query, expect, typ, category):
    if (query, expect) in used:
        return False
    used.add((query, expect))
    data.append({'id': len(data) + 1, 'query': query, 'expect': expect, 'type': typ, 'category': category})
    return True

def round_robin_articles(max_per_law=4):
    picks = []
    for idx in range(max_per_law):
        for law in laws:
            if idx < len(law['articles']):
                num, text = law['articles'][idx]
                picks.append({'law': law['law'], 'article': num, 'text': text})
    return picks

picks = round_robin_articles(10)

for item in picks:
    if sum(1 for x in data if x['type'] == '精确查询') >= 50:
        break
    law = item['law']; num = item['article']
    templates = [f'{law}{num}规定了什么？', f'请解释{law}{num}的内容', f'{law}中{num}是怎么规定的？']
    add(templates[len(data) % 3], f'{law},{num}', '精确查询', law)

semantic_templates = [
    '关于{topic}，法律上一般怎么规定？',
    '如果发生{topic}纠纷，可以参考什么法律依据？',
    '{topic}相关的权利义务怎么认定？',
    '普通人遇到{topic}问题应该看哪些规定？',
    '{topic}出问题后责任一般怎么承担？'
]
for item in picks:
    if sum(1 for x in data if x['type'] == '语义查询') >= 180:
        break
    law = item['law']; num = item['article']
    terms = article_terms(item['text'], law)
    topic = '、'.join(terms[:2])
    add(semantic_templates[(len(data) + len(topic)) % len(semantic_templates)].format(topic=topic), f'{law},{num}', '语义查询', law)

by_area = defaultdict(list)
for item in picks:
    by_area[law_priority(item)].append(item)
cross_templates = [
    '如果同时涉及{a}和{b}，应当优先参考哪些法律依据？',
    '发生{a}后又出现{b}，责任和处理程序怎么判断？',
    '{a}纠纷中还涉及{b}，当事人可以主张哪些权利？',
    '处理{a}问题时，如果对方又提出{b}，法律依据是什么？'
]
for _, arr in sorted(by_area.items()):
    for i in range(0, len(arr) - 1, 2):
        if sum(1 for x in data if x['type'] == '跨法条综合查询') >= 60:
            break
        first = arr[i]; second = arr[i + 1]
        a = article_terms(first['text'], first['law'])[0]
        b = article_terms(second['text'], second['law'])[0]
        add(cross_templates[(i // 2) % len(cross_templates)].format(a=a, b=b), f"{first['law']},{first['article']}", '跨法条综合查询', first['law'])
    if sum(1 for x in data if x['type'] == '跨法条综合查询') >= 60:
        break

boundary_templates = ['这种情况能不能要求赔偿？', '对方这样做是不是违法？', '我能不能直接起诉对方？', '这个协议现在还有效吗？', '我这种情况大概能拿到多少钱？', '单位这样处理我应该怎么办？']
for item in picks:
    if sum(1 for x in data if x['type'] == '边界/事实缺失查询') >= 30:
        break
    topic = domain_terms(item['law'])[0]
    add(topic + '，' + boundary_templates[len(data) % len(boundary_templates)], f"{item['law']},{item['article']}", '边界/事实缺失查询', item['law'])

# Top up positives if duplicates reduced totals.
for item in picks:
    if len(data) >= 320:
        break
    topic = domain_terms(item['law'])[len(data) % len(domain_terms(item['law']))]
    add(f'{topic}在法律上怎么处理？', f"{item['law']},{item['article']}", '语义查询', item['law'])

negative_groups = [
    ('刑法量刑', ['盗窃罪会判几年？', '故意伤害罪轻伤二级怎么量刑？', '诈骗五万元会坐牢多久？', '取保候审需要满足什么刑事条件？', '缓刑适用条件是什么？']),
    ('专利商标', ['发明专利被别人抄袭怎么起诉？', '商标近似侵权怎么判断？', '外观设计专利无效宣告怎么申请？', '软件著作权登记流程是什么？', '专利权利要求怎么解释？']),
    ('税务稽查', ['企业偷税漏税会被怎么处罚？', '税务稽查补税滞纳金怎么算？', '增值税专用发票虚开有什么后果？', '企业所得税汇算清缴怎么调整？', '个人直播收入税务怎么申报？']),
    ('非法律问题', ['杭州明天天气怎么样？', '帮我写一首关于春天的诗', '高考英语作文怎么提分？', '感冒发烧应该吃什么药？', '去北京旅游三天怎么安排？']),
    ('事实不足', ['我现在很烦怎么办？', '对方太过分了我该怎么办？', '这件事是不是我的错？', '我感觉被欺负了怎么办？', '他们这样合理吗？']),
    ('虚构法规', ['民法典第9999条规定了什么？', '浙江省宠物继承条例怎么规定？', '网络聊天赔偿法第十八条是什么？', '个人情绪保护法可以起诉吗？', '中华人民共和国恋爱合同法有效吗？']),
    ('境外法律', ['美国加州离婚财产怎么分？', '日本租房押金法律怎么规定？', '欧盟GDPR罚款标准是多少？', '新加坡公司注册法律流程是什么？', '英国劳动合同解雇补偿怎么算？']),
    ('刑事程序', ['刑事拘留最长多少天？', '检察院批捕后还能不起诉吗？', '刑事案件二审会不会加刑？', '公安立案后多久必须结案？', '刑事附带民事赔偿怎么提？'])
]
negatives = []
for cat, qs in negative_groups:
    for q in qs:
        negatives.append((q, cat))
base = list(negatives)
variants = ['请直接给出依据。', '需要准备什么材料？', '一般多久能处理？', '有没有具体标准？']
idx = 0
while len(negatives) < 80:
    q, cat = base[idx % len(base)]
    negatives.append((q + variants[len(negatives) % len(variants)], cat))
    idx += 1
for q, cat in negatives[:80]:
    add(q, 'NOT_IN_KB', '负面测试', cat)

for i, row in enumerate(data[:400], 1):
    row['id'] = i
data = data[:400]
out.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
print('generated', len(data))
print('type_counts', Counter(x['type'] for x in data))
print('negative', sum(1 for x in data if x['expect'] == 'NOT_IN_KB'))
print('law_count', len(set(x['category'] for x in data if x['expect'] != 'NOT_IN_KB')))
print('source_laws', len(laws))
