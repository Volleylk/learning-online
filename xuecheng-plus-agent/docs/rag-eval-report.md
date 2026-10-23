# RAG / Agent 评测报告
## 一、评测范围
- 测试集：`data/eval_set.json`，共 **48 题**，6 份演示讲义 × 8 题
- 检索 K 值：Top-5；生成链路：完整 Agent 工具调用（查询改写→混合检索→rerank→回答）
- 裁判模型：qwen3.7-max-2026-06-08（失败降级 qwen-plus），两次调用取均值
- 评测时间：2026-10-23 21:38:48
## 二、检索层指标（rerank on/off 对比）
| 指标 | rerank off | rerank on | 说明 |
|---|---|---|---|
| Recall@5 | 100.00% | 100.00% | 期望关键词命中率 |
| Precision@5 | 35.07% | 38.54% | TopK 中命中结果占比 |
| MRR | 0.990 | 1.000 | 第一个命中结果的倒数排名 |
| 跨课误召回(题均) | 2.27 | 1.69 | 平均每题召回的非目标讲义结果数 |

## 三、生成层指标（LLM-as-Judge）
| 课程 | 题数 | faithfulness | answer_relevancy |
|---|---|---|---|
| Java面向对象三大特性.txt | 8 | 0.537 / 8 | 0.994 / 8 |
| 微服务架构与SpringCloud.txt | 8 | 0.562 / 8 | 0.863 / 8 |
| 高等数学：导数与极限.txt | 8 | 0.637 / 8 | 0.994 / 8 |
| 数据结构与算法基础.txt | 8 | 0.537 / 8 | 0.963 / 8 |
| 大学英语（一）.txt | 8 | 0.469 / 8 | 1.000 / 8 |
| 计算机网络原理.txt | 8 | 0.481 / 8 | 0.956 / 8 |
| **整体** | 48 | **0.537** / 48 | **0.961** / 48 |

## 四、逐题明细
| 题ID | 问题 | Recall(off→on) | MRR(off→on) | 串课(off→on) | faith | rel | 工具调用 |
|---|---|---|---|---|---|---|---|
| 1 | 面向对象编程的三大基本特性是什么… | 1→1 | 1.00→1.00 | 3→2 | 0.9 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：user_preference_read… |
| 2 | Java中封装通过什么修饰符控制成员可见性… | 1→1 | 1.00→1.00 | 3→2 | 0.3 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 3 | Java中使用什么关键字实现继承，继承有什么特点… | 1→1 | 1.00→1.00 | 3→2 | 0.5 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 4 | 多态的三个必要条件是什么… | 1→1 | 1.00→1.00 | 3→2 | 1.0 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 5 | 里氏替换原则的内容是什么… | 1→1 | 1.00→1.00 | 4→3 | 0.25 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 6 | 开闭原则的含义是什么… | 1→1 | 1.00→1.00 | 4→2 | 0.35 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 7 | 为什么说Java的继承是单继承… | 1→1 | 1.00→1.00 | 3→2 | 0.25 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 8 | 良好的Java类设计如何配合三大特性… | 1→1 | 1.00→1.00 | 3→2 | 0.75 | 0.95 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_info_query…、正在调用工具：course_info_query… |
| 9 | 什么是微服务架构… | 1→1 | 1.00→1.00 | 1→1 | 0.4 | 0.8 | 正在调用工具：course_lecture_search…、正在调用工具：user_preference_read… |
| 10 | 微服务架构相比单体架构的优势是什么… | 1→1 | 1.00→1.00 | 3→2 | 0.35 | 0.75 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_info_query…、正在调用工具：course_info_query… |
| 11 | 注册中心在服务发现中起什么作用… | 1→1 | 1.00→1.00 | 1→1 | 0.4 | 0.85 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 12 | Nacos是什么，有什么功能… | 1→1 | 1.00→1.00 | 3→3 | 0.6 | 0.9 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 13 | 网关在微服务架构中的作用是什么… | 1→1 | 1.00→1.00 | 3→2 | 0.55 | 0.85 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 14 | Spring Cloud的核心组件有哪些… | 1→1 | 1.00→1.00 | 3→2 | 0.55 | 0.85 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_info_query…、正在调用工具：course_info_query… |
| 15 | OpenFeign提供了什么能力… | 1→1 | 1.00→1.00 | 2→2 | 0.75 | 0.9 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 16 | Sentinel的三大能力是什么… | 1→1 | 1.00→1.00 | 3→2 | 0.9 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 17 | 函数极限的定义是什么… | 1→1 | 1.00→1.00 | 2→1 | 0.65 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 18 | 极限的四则运算法则是什么… | 1→1 | 1.00→1.00 | 1→1 | 0.5 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 19 | 第一个重要极限是什么… | 1→1 | 1.00→1.00 | 2→1 | 0.35 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 20 | 第二个重要极限的结论是什么… | 1→1 | 1.00→1.00 | 2→1 | 0.6 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 21 | 导数的定义是什么… | 1→1 | 1.00→1.00 | 1→1 | 0.8 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 22 | 导数的几何意义是什么… | 1→1 | 1.00→1.00 | 0→0 | 0.9 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_info_query… |
| 23 | 复合函数求导的链式法则是什么… | 1→1 | 1.00→1.00 | 1→1 | 0.6 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 24 | 可导与连续的关系是什么… | 1→1 | 1.00→1.00 | 3→2 | 0.7 | 0.95 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 25 | 栈的进出原则是什么… | 1→1 | 1.00→1.00 | 4→3 | 0.65 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search…、正在调用工具：course_info_query… |
| 26 | 队列的进出原则是什么… | 1→1 | 1.00→1.00 | 3→2 | 0.75 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 27 | 数组随机访问的时间复杂度是多少… | 1→1 | 0.50→1.00 | 3→2 | 0.45 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 28 | 二叉搜索树的结点值有什么规律… | 1→1 | 1.00→1.00 | 2→2 | 0.45 | 0.8 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 29 | 二叉树中序遍历的顺序是什么… | 1→1 | 1.00→1.00 | 2→2 | 0.55 | 0.9 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 30 | 快速排序的平均时间复杂度是多少，用什么思想… | 1→1 | 1.00→1.00 | 3→2 | 0.55 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 31 | 使用二分查找的前提条件是什么… | 1→1 | 1.00→1.00 | 2→2 | 0.4 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：user_preference_read… |
| 32 | 邻接矩阵适合存储什么样的图… | 1→1 | 1.00→1.00 | 2→2 | 0.5 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 33 | 现在完成时的结构是什么… | 1→1 | 1.00→1.00 | 3→2 | 0.45 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 34 | 过去完成时表示什么… | 1→1 | 1.00→1.00 | 0→0 | 0.75 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 35 | 与现在事实相反的虚拟语气怎么构成… | 1→1 | 1.00→1.00 | 1→1 | 0.5 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 36 | 被动语态的基本结构是什么… | 1→1 | 1.00→1.00 | 3→2 | 0.5 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 37 | 定语从句中关系代词which指代什么… | 1→1 | 1.00→1.00 | 3→2 | 0.5 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 38 | 动名词作主语是什么用法… | 1→1 | 1.00→1.00 | 1→1 | 0.25 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 39 | It is加形容词加for sb to do句型是什… | 1→1 | 1.00→1.00 | 0→0 | 0.25 | 1.0 | - |
| 40 | the more the more 句型表示什么… | 1→1 | 1.00→1.00 | 1→1 | 0.55 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 41 | OSI参考模型分为哪七层… | 1→1 | 1.00→1.00 | 3→2 | 0.45 | 0.95 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 42 | TCP三次握手的过程是什么… | 1→1 | 1.00→1.00 | 3→2 | 0.6 | 0.95 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 43 | TCP四次挥手的过程是什么… | 1→1 | 1.00→1.00 | 2→2 | 0.15 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 44 | TCP和UDP有什么区别… | 1→1 | 1.00→1.00 | 2→2 | 0.4 | 0.95 | 正在调用工具：course_lecture_search…、正在调用工具：user_preference_read… |
| 45 | HTTP协议默认端口是多少，有什么特点… | 1→1 | 1.00→1.00 | 1→1 | 0.45 | 0.9 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 46 | 子网掩码的作用是什么… | 1→1 | 1.00→1.00 | 2→2 | 0.55 | 1.0 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 47 | C类私有地址的范围是什么… | 1→1 | 1.00→1.00 | 3→2 | 0.75 | 0.95 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |
| 48 | DNS的作用是什么… | 1→1 | 1.00→1.00 | 3→2 | 0.5 | 0.95 | 正在调用工具：course_lecture_search…、正在调用工具：course_lecture_search… |

## 五、结论与局限
**检索层（rerank on vs off）**
- Recall@5 两端均为 100%，说明"向量 + BM25 + RRF"混合检索对 48 题全部命中期望关键词，召回能力已满。
- MRR 0.990 → 1.000：rerank 后所有题的第一个命中结果都排在 Top1，命中排序被精排修正。
- 跨课误召回 2.27 → 1.69（降 25.6%）：qwen3-rerank 把其他讲义的干扰结果往后压，跨课串扰得到有效抑制。
- Precision@5 35.07% → 38.54%：Top5 中目标讲义内容占比提升（演示讲义内容简、近似题多，绝对值偏低属正常）。

**生成层（LLM-as-Judge，qwen3.7-max）**
- answer_relevancy 整体 **0.961**：回答普遍切题、直接回答提问，相关性表现优秀。
- faithfulness 整体 **0.537**：中等偏低。典型低分题（TCP 四次挥手 0.15、动名词 0.25、单继承 0.25）的共性是讲义只给了概念主干，Agent 回答时用常识补全了细节（如挥手各阶段的 FIN/ACK 时序），超出检索上下文 → 忠实度被扣分。这与"演示讲义内容简、部分题需常识补全"直接相关，属于可接受的回答风格，但若要求严格忠于讲义，应在系统提示中强化"仅依据讲义内容回答、不补充常识"约束后再评测。

**局限**
- 测试集基于 6 份演示讲义人工标注，规模有限（48 题）；
- 裁判为模型评分（LLM-as-Judge），存在评分偏差可能；`None` 表示该题裁判调用失败未计入（本次无 None）；
- faithfulness 的评分尺度对"讲义外常识补充"较敏感，与业务预期的忠实度口径需结合场景解读。

## 六、复现
```bash
venv\Scripts\python scripts\eval_full.py          # 全量
venv\Scripts\python scripts\eval_full.py --limit 6   # 前 6 题
```
