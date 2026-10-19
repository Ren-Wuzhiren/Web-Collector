# agent-kit — 一套"丢给 AI 就能开工"的协作包

这是 `prompt-system` v1.0.0 的补丁：那套卡片解决的是"每次粘贴什么提示词"，这套解决的是**"不用粘贴，AI 自己就会读"**。

差别在于加载方式：

| | prompt-system | agent-kit |
|---|---|---|
| 形态 | 提示词卡片库 | 项目仓库里的约定文件 |
| 触发 | 我手动复制粘贴 | Agent 开新会话自动读取 |
| 内容 | 单场景指令 | 身份 + 语气 + 契约 + 全流程 |
| 跨模型 | 靠复制 | 靠 `AGENTS.md` 这个事实标准 |

---

## 四个文件的分工

| 文件 | 放在哪 | 谁读 | 作用 |
|---|---|---|---|
| `AGENTS.md` | 每个项目仓库**根目录** | 所有 Agent 自动读 | 我是谁、怎么说话、怎么干活 |
| `skills/project-dev/SKILL.md` | `~/.claude/skills/` 或项目 `.dsh/skills/` | 需要时按需加载 | 13 步流程的详细方法 |
| `templates/stage-card.md` | 复制到项目 `docs/STAGE-TEMPLATE.md` | 每个二级阶段必读 | 阶段卡的输出格式（完整版 + 精简版） |
| `profiles/learner.md` | 随便放，`AGENTS.md` 里引用 | 我手动贴给 Agent | 完整背景档案，给新工具用 |

`AGENTS.md` 控制在 200 行内——它是**每次会话都要占上下文**的东西，写多了反而稀释重点。细则全部放 `SKILL.md`。

---

## 第一次怎么用（3 步）

### 1. 建一个新项目

```bash
mkdir my-project && cd my-project
git init
```

### 2. 把约定文件放进项目根目录

```bash
# 复制常驻约定
copy D:\计算机杂谈\agent-kit\AGENTS.md .

# 建一个项目卡（这个每个项目都不一样）
copy D:\计算机杂谈\agent-kit\profiles\PROJECT.template.md PROJECT.md

# 放下阶段卡模板，Agent 每次开工照着它输出
mkdir docs
copy D:\计算机杂谈\agent-kit\templates\stage-card.md docs\STAGE-TEMPLATE.md
```

然后用编辑器把 `PROJECT.md` 填上——只填你确定的部分，剩下的写"待定"，Agent 会问你。

### 3. 开一个新会话，说一句话

```
按 AGENTS.md 的协商模式，帮我把这个项目切成一级阶段和二级阶段。
```

它会先给方案、再问问题、最后落成 `docs/ROADMAP.md`。**不会直接甩代码。**

---

## 各工具怎么让它自动生效

| 工具 | 自动读取的文件 | 说明 |
|---|---|---|
| **Claude Code** | `CLAUDE.md` | 建一个 `CLAUDE.md`，内容只写一行：`@AGENTS.md`。这样两边共用一份。 |
| **Codex / Cursor** | `AGENTS.md` | 放根目录即可，不用改。Cursor 也可写 `.cursor/rules/*.mdc`。 |
| **DeepSeek Harness** | `AGENTS.md` | 同上。`SKILL.md` 放 `.dsh/skills/<名字>/SKILL.md`。 |
| **网页版 ChatGPT / Claude** | 无 | 用 Projects 的"自定义指令"贴 `AGENTS.md` 的 §1–§3，或用 `profiles/learner.md` 精简版。 |

**跨工具的通用做法**：`AGENTS.md` 是真身，`CLAUDE.md` 只做一行引用。别维护两份。

---

## 什么时候该改这套东西

不要一开始就想设计完美。按这个节奏迭代：

1. 跑完一个项目（哪怕是"给它加一个功能"）。
2. 结束后问自己：这次 Agent 在哪儿让你想骂人？
3. 只改那一处——要么加进 `AGENTS.md` §8（我不喜欢的做法），要么改 `SKILL.md` 对应步骤。
4. 改完在 `CHANGELOG.md` 记一行：改了什么、因为哪次翻车。

**看到一次翻车才改一条，不要预先写 50 条规则。** 规则太多，Agent 会挑着看，反而等于没有。

---

## 怎么改阶段卡模板

模板不是一次写成的，是**删出来的**。

1. 用完整版跑 2–3 个二级阶段。
2. 每次都空着的那几节，删掉或合并——空栏目会诱使 Agent 编内容。
3. 每次都写得很敷衍的那几节（比如"坑"只剩"要注意边界"），把要求写狠一点。
4. 删改都记进 `CHANGELOG.md`，写清是因为哪次输出不合格。

**顺序很重要**：先有约束，再删约束。一上来就精简，Agent 会退回"给一段代码完事"。

---

## 和 prompt-system 怎么配合

- `prompt-system/` 继续留着：里面 `20-dev/` 的架构、调试、审查卡片比 `SKILL.md` 细，需要深挖某一步时单独复制过去用。
- `40-profiles/` 里的学习者卡、项目卡模板就是 `profiles/` 的来源，字段一样，不用重填。
- `50-playbooks/` 的剧本（new-feature / bug-fix）可以作为"二级阶段"的另一种切法参考。

一句话：**`agent-kit` 负责"每次自动就位"，`prompt-system` 负责"深挖时查手册"。**

---

## 目录

```
agent-kit/
├── AGENTS.md                       # 常驻约定（复制到每个项目根目录）
├── README.md                       # 本文件
├── CHANGELOG.md
├── profiles/
│   ├── learner.md                  # 学习者完整档案
│   └── PROJECT.template.md         # 项目卡模板
├── templates/
│   └── stage-card.md               # 二级阶段卡模板（完整版 + 精简版）
└── skills/
    └── project-dev/
        └── SKILL.md                # 13 步流程详解
```
