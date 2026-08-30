请基于资料包和可用工具，为当前用户生成可执行的旅行计划。

【基本信息】
- 途经城市：{{city_names}}
- 城市停留：
{{city_stays}}
- 开始日期：{{start_date}}
- 结束日期：{{end_date}}
- 总天数：{{travel_days}}
- 交通方式：{{transportation}}
- 住宿偏好：{{accommodation}}
- 明确偏好：{{preferences}}
- 额外要求：{{free_text_input}}
- 输出语言：{{language}}

【并行研究产生的 Context Pack】
{{planning_context}}

【结构化输出格式】
{{format}}

【最终数据约束】
1. days 数组长度必须等于总天数，day_index 从 0 开始连续递增。
2. 每天通常安排 2-3 个景点，移动日可以 1-2 个；每天必须有具体 hotel。
3. 每天 meals 必须包含 type=breakfast、lunch、dinner，餐饮类别不能写入 type。
4. attractions、hotel、meals 使用工具返回的正式名称、地址和经纬度；无法核实时减少候选，不得编造坐标。
5. longitude 是经度、latitude 是纬度，严禁写反。
6. 多城市切换日设置 is_transfer_day=true，避免跨城市地点被放入同一天。
7. 每个景点 description 控制在 60～100 个汉字，每日 description 不超过 120 个汉字，overall_suggestions 不超过 200 个汉字；包含最关键的一项预约、交通或避坑提示即可。
8. image_url 必须为空字符串，图片由后端真实数据源补充。
9. 餐厅不得进入 attractions；景点不得进入 meals。
10. budget.total 必须等于门票、酒店、餐饮、市内交通和城际交通分项之和。
11. 不在 description 中重复名称、地址、坐标、日期和工具来源，避免无意义扩写。
12. 只输出一个 JSON 对象。
