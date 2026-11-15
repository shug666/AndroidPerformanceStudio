## MODIFIED Requirements

### Requirement: 投屏画面实时解码渲染

应用 SHALL 持续读取视频流、解码 H.264 帧，并按到达顺序渲染到画布；画布 SHALL 保持设备屏幕宽高比，窗口尺寸变化时按比例缩放（默认 contain 模式，不裁切不拉伸）。为使软解消费能力与编码产出平衡、长会话不因通道积压而中断，应用 SHALL 通过服务器启动参数限制编码流（分辨率上限、帧率上限、码率上限）。解码落后时应用 SHALL 只渲染最新帧（合流），解码失败或流中断时，应用 SHALL 停止渲染并呈现会话状态（错误或已断开），不得展示过期画面并暗示其为实时画面。

应用 SHALL 以 FFmpeg（JavaCV）软解为主解码后端；FFmpeg 所需的 native 库 SHALL 随目标宿主平台分发——构建时按目标操作系统与架构选择对应的 bytedeco ffmpeg/javacpp 平台 native 制品（覆盖 Windows x64、Linux x64/arm64、macOS x64/arm64），不得在非 Linux 宿主上只携带 Linux native 库导致 FFmpeg 无法初始化。当 FFmpeg native 库在目标宿主上加载失败（库损坏、运行时依赖缺失）时，应用 SHALL 降级到 jcodec 纯 Java 解码并提示用户已降级；降级 SHALL 是 native 库异常的最终回退路径，而非平台分发的预期结果。

#### Scenario: 实时画面按宽高比显示
- **WHEN** 投屏会话处于运行状态且视频帧持续到达
- **THEN** 画布按设备屏幕宽高比缩放显示最新帧，无裁切无拉伸

#### Scenario: 编码流降载保持长会话稳定
- **WHEN** 投屏会话长时间运行（远超解码单帧延迟对应的产出周期）
- **THEN** 服务器按受限的分辨率/帧率/码率产出，解码持续跟上，会话不因缓冲积压或超时被终止

#### Scenario: 流中断时停止渲染
- **WHEN** 视频 socket EOF 或解码器报错
- **THEN** 会话进入「已断开」或「失败」状态，画布停止更新，UI 提示会话已中断并可重试

#### Scenario: Windows 宿主以 FFmpeg 软解运行
- **WHEN** 应用在 Windows x64 宿主上运行投屏且 FFmpeg native 库分发正确
- **THEN** 投屏以 FFmpeg 软解运行，不出现降级横幅，不依赖 jcodec 回退

#### Scenario: native 库加载失败时降级到 jcodec
- **WHEN** FFmpeg native 库在目标宿主上加载失败（库损坏或运行时依赖缺失）
- **THEN** 应用降级到 jcodec 纯 Java 解码、强制低档位，UI 提示已降级及降级原因；投屏不致完全不可用
