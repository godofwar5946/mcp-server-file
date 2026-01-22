一个基于 Spring AI 的 MCP 文件系统服务（读/写/列表）。

支持的工具：
- `fs_list_roots`：列出允许访问的根目录（白名单）
- `fs_list_directory`：列出目录内容（非递归，支持分页与 glob 过滤；支持 refresh=true 强制刷新缓存）
- `fs_list_tree`：递归列出目录树（支持 maxDepth/maxEntries；可 includeFiles=false 只返回目录）
- `fs_read_file`：读取文件内容（文本 utf-8 / 二进制 base64，可截断）
- `fs_read_step_model_info`：解析 STEP(.stp/.step) 模型信息（含装配层级/零件BOM/尺寸与PMI摘要/几何与拓扑摘要，支持中文）
- `fs_read_step_entities`：分页列出 STEP(.stp/.step) 的 DATA 段实体（可按实体类型过滤，便于读取装配/几何/尺寸等细节）
- `fs_read_file_lines`：按行读取（用于分片拉取完整文本，降低被客户端截断的概率）
- `fs_read_file_filtered`：筛选读取（按行号范围/关键字/正则，返回行号+文本，可选上下文）
- `fs_read_file_range`：按字节范围读取（base64，用于分片拉取任意文件）
- `fs_find_by_name`：按名称定位文件/目录（优先缓存，未命中或 refresh=true 时回退扫描并更新缓存）
- `fs_search`：搜索文件/目录（返回匹配行号+片段，不返回整文件）
- `fs_prepare_patch_file`：按规则生成补丁写入（正则替换/插入/删除/按行处理；不直接写入，需 confirm）
- `fs_prepare_write_file` -> `fs_confirm_write_file`：两段式写入确认（confirm=true 才会真正写入）
