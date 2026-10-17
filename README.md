# 糯叽（Nuoji，原名灵犀AI）

> Android 端 AI 对话助手 · 大模型随你选 · 不用注册账号 · 没有广告

当前版本：**v1.193**（支持 Android 7.0 及以上手机）

## 项目简介

糯叽 是一个开源的 Android 聊天 + 手机操控客户端，接入任何兼容 OpenAI 接口格式的大模型（OpenAI / DeepSeek / 通义 / 智谱 / Kimi / Groq / 硅基流动 / 自建中转等），在本机填写自己的 API Key 即可使用。所有配置只保存在手机本地，不写入代码、不随安装包分发。

## 核心功能

- **多模型接入**：兼容 OpenAI 格式的服务都能配，支持多套配置随时切换，可分别设置温度、最大 token、是否支持图片
- **免费模型引导**：设置与聊天页「+」面板都有入口，一键复制并打开官网、按步骤拿到 Key，并可把接口地址与模型名先存成一条配置
- **AI 角色系统**：内置多种角色（通用助手、编程助手、翻译、写作等），也可自定义角色提示词
- **语音输入**：设备自带识别优先；不支持的设备走云端识别，并可自动回退到已配置的免费语音服务（设置页提供硅基流动 / Groq 免费额度预设，只需粘贴 Key）
- **语音朗读**：长按任意 AI 回答即可朗读
- **图片提问**：视觉模型可直接发图提问，支持拍照、相册、剪贴板粘贴
- **文件解析**：拖入文本类文件（txt / md / 代码 / json 等）作为提问上下文
- **联网搜索**：内置 DuckDuckGo 免费搜索，免 Key；开启后 AI 会结合最新网络资料回答，并在气泡下方列出可点击的参考来源
- **手机操控**：点亮聊天页操控开关后，把要做的事说出来，AI 会自行读屏并替你点按、滑动、输入完成操作。前台模式接管屏幕边看边操作；后台模式不读屏不占屏，只发指令（拉起应用、打开链接、只读查询设备信息）。操控通道可在设置里选无 Root（默认，系统无障碍）/ Root / Shizuku；自带急停、默认拦截支付类按钮、密码框内容不回传、单任务步数上限
- **打字机输出 + 随时停止**：回答逐字上屏，生成中点发送键即可打断
- **多版本回答**：同一条问题可重新生成多次，气泡下方左右切换查看不同版本
- **一打开就是聊天页**：启动直接进主界面，不需要多走一步
- **液态玻璃**：聊天页顶栏、输入栏，以及手指特效页的上下两条栏，都是真正的玻璃——把身后正在滚的聊天记录模糊一层贴进面板，靠边一圈折射般的放大带、上缘镜面高光、下沿一抹内阴影；列表滚动或回答逐字输出时倒影实时跟上，焦点落到输入框会亮起主色描边，按住鼓起、高光跟手指、松手过冲回弹；设置里可整体关掉，轻量档机器自动只画半透明底
- **手指特效**：输入栏下方一条玻璃切换条，「AI」与「手指特效」两格切换（选中那格有一块会滑的染色胶囊）。进特效先要摄像头权限，把手伸到镜头前：五指张开时指间浮出一条流动的星河、指尖各一颗亮星跟手走；握拳整只手石化（灰白石面 + 裂纹 + 指缝落灰），再张开又流回星河。底栏「录一段」最多 20 秒，「拍一张」把当前这一帧连特效存成照片，都落在相册（Movies/糯叽、Pictures/糯叽）；顶栏与底栏透出并模糊身后的相机画面。识别全部在手机本机完成，画面不联网、不上传；所需识别文件已随安装包内置，第一次进特效时自动放到内部储存「特效模型」文件夹，之后一直从那儿读，设置 → 手指特效的识别文件 可看状态或删除
- **应用商店与侧载**（设置 → 应用商店与已装应用）：可把你信任的安装包直链添加进来下载，带实时进度，装前先校验大小、包结构与关键文件，不合格的包直接扔掉不交安装；也能扫内部储存里已有的安装包一键交给系统安装；还会列出手机上已装的应用并一键启动（卸载请到系统设置）
- **会话管理**：会话历史列表 + 标题/正文全文搜索（命中处标黄）、草稿自动保存、跨天日期分隔
- **多种导出**：Markdown 文本 / .md 文件 / 长图 PNG / 复制全文
- **应用内更新**：打开就能看有没有新版本，也能翻以前每个版本改了什么；下载时几条线路同时开跑，最先下完并检查没坏的那条算数，其余自动停；某条线路 10 秒没速度就当场换线，损坏的安装包绝不装
- **历史版本回退**：可下载任意历史版本；降级安装前会明确提示卸载风险
- **低端机优化**：所有动画共用一条刷新节拍，屏幕外的不再绘制；老机器只用画得省的进度条动画，聊天列表与头像的动效自动关掉
- **下载动画预览**：聊天页「+」面板里的「下载动画」按钮，选择页十五款同时现场跑动画（普通横条 / 贪吃蛇 / 像素方块 / 液体波动 / 圆环表盘 / 吃豆人 / 小火车 / 电池充电 / 火柴人跑步 / 流星拉尾 / 齿轮咬合 / 均衡器 / 心电图 / 小船过河 / 毛毛虫），点哪一款放大播一轮、播完自动回到选择页，可重播可随时返回；纯演示不联网
- **历史安装包存档**：只有从「历史版本」下载安装才会在内部储存「历史记录」文件夹留一份 APK（普通更新不存档，装完不留冗余文件），文件管理器可直接查看安装；设置页「历史安装包」列出已存版本，支持一键安装/删除，并可改保存位置
- **文件与文件夹**：在自己手机上建文件夹、改名、复制、挪走、往里搜索、看属性和剩余空间；删除先进回收站，随时能还原
- **操控台**：现场实测「现在能不能动手、走的是哪条路」，一键试读屏幕自检，八件常见的事点一下就走，随时紧急停止，还能让糯叽直接整理手机文件
- **改名说明**：v1.175 起「灵犀AI」更名为「糯叽」，桌面图标与列表里以新名字出现；包名、下载地址与已存配置保持不变，老版本可以直接覆盖升级
- **深浅双主题 + 字号缩放**，全部设置项可导出/导入备份
- **安装包大小**：26.0MB——手部特效的推理库两份架构约 17.1MB、内置识别文件打包后 5.6MB，其余才是聊天本体；嫌大可以说明，只留 64 位能小约 7MB
- **动画**：新消息上浮淡入、流式光标闪烁、头像呼吸、页面滑动转场、发送触觉反馈（尊重系统「关闭动画」设置）

## 下载

| 平台 | 地址 |
|---|---|
| GitHub | https://github.com/54188jk/lingxi-chat/releases/download/v1.192/lingxi-v1.192.apk |
| Gitee | https://gitee.com/wuzhuf/lingxi-chat/releases/download/v1.192/糯叽-v1.192-release.apk |
| CNB 镜像 | https://cnb.cool/tuui.rrj/lingxi-chat-release/-/releases/download/v1.192/lingxi-v1.192.apk |


应用内「设置 → 检查更新」会同时试这几处地址和几个加速站，谁先把完整包下下来就用谁。

## 版本更新历史

面向用户的完整更新公告见 [CHANGELOG.md](CHANGELOG.md)。

| 版本 | 更新内容 |
|---|---|
| v1.193 | 发布：2026-10-17 12:06:10。手指特效在 Android 9 以前的手机上也能用了（之前点进去只会提示「这份文件读不起来」）；特效页出错时会把机型和系统版本写进提示，长按可复制发回；安装包约 32.1MB |
| v1.192 | 发布：2026-10-17 11:17:37。手指特效页新增「拍一张」；修复特效偶尔让应用突然退出、录出来的片段颜色发灰偏绿；特效跟手更顺，不再拖慢整个应用 |
| v1.191 | 发布：2026-10-16 23:51:17。手指特效页的顶栏和底栏换成真玻璃，透出相机里的画面，手移动时里面的光影跟着动 |
| v1.190 | 发布：2026-10-16 23:09:00。安装包多挂一个国内镜像（只放发布版附件的镜像仓），更新时与其它线路一起竞速；下载线路清单合并成一处统一维护，加速站由 6 个扩到 7 个；应用商店里填 GitHub 发布直链的安装包也会自动走这几条加速线路 |
| v1.189 | 发布：2026-10-16 22:41:55。修「更新一直停在正在连线路」：上一版逐条探测线路能力、探测全无声就把线路当成不存在导致下载没开始；现在拿到地址立即整包开跑，探测改在后台做，探到支持分段的线路再加入。失败原因留在页面上不再一闪而过；包完整性判定以线路自报长度为准 |
| v1.188 | 发布：2026-10-16 22:08:18。手指特效的手部识别文件改为随安装包内置（不再联网下载那 7.5MB），第一次用到时自动复制到本机永久目录「特效模型」并做内容核对，对不上自动重放；读失败时把「文件坏了」和「本机运行库加载不了」分开报。安装包 20.4MB → 26.0MB |
| v1.187 | 发布：2026-10-16 21:43:52。手指特效那份识别文件下完后多加一道内容核对，对不上自动删掉重下；真读不起来时界面写清卡在「文件坏了」还是「本机运行库加载不了」。下载改成两条腿同时跑：支持分段的线路切块并发抓、缺哪块补哪块，不支持分段的线路整包竞速、12 秒没进账就换线，先下完整的一份先用；应用商店里下载自己添加的安装包也走这套 |
| v1.186 | 发布：2026-10-16 20:53:30。新增手指特效：输入栏下方玻璃分段切换条（AI / 手指特效），摄像头实时看手——五指张开指间星河流光、指尖亮星跟手，握拳整只手石化带裂纹与落灰，可录 20 秒合成同一份画面存进相册；识别在手机本机完成、画面不上传，首次进入按需下载约 7.5MB 识别文件到内部储存「特效模型」，设置里可看状态与删除；为特效不卡与断网可用，安装包涨到约 20MB |
| v1.185 | 发布：2026-10-16 19:16:41。设置里的「液态玻璃」开关从「外观与输入」那组的最末尾挪到「主题 / 字号」下面第一行，一路翻到底才能看到的毛病改掉；它的说明文字原来还在讲已经删掉的桌面，现在写的是聊天页顶栏和输入栏的实际效果 |
| v1.184 | 发布：2026-10-15 12:48:56。修掉上一版一打开就退出去的闪退：玻璃抓身后那一层的时机从「画面画到一半」挪到「画面空下来那一刻」，并固定复用两块小图不再反复申请；某台机器抓不动时玻璃自动退回半透明底并把开关关掉，不再重复踩坑；聊天页第一屏建议只留一张卡、字号放大 |
| v1.183 | 发布：2026-10-15 12:17:08。删掉「模仿手机桌面」那层中间页，打开糯叽直接就是聊天主界面；液态玻璃搬进主界面——顶栏与输入栏透出并模糊正在滚动的聊天记录，滚动与流式输出时倒影实时跟随，焦点亮起主色描边，按住鼓起、松手回弹；应用商店与已装应用挪到设置里，功能不减；顺带清掉桌面遗留的壁纸素材、机桌主题与不再被引用的聚焦态输入框图 |
| v1.182 | 发布：2026-10-14 20:12:42。手机桌面的面板换成真·液态玻璃：把身后壁纸模糊后贴进面板、边缘一圈折射放大带、上缘镜面高光、下沿内阴影；按下鼓起、高光跟随手指、松手过冲回弹；时钟卡片、应用图标圆牌、Dock、三键条、多任务卡片全部换装；设置新增「液态玻璃」开关，轻量档自动退化为半透明纯色底 |
| v1.181 | 发布：2026-10-10 22:20:15。打开糯叽直接进聊天主界面，不再先经过桌面那一层；手机桌面退成设置 → 手机桌面的可选入口；桌面内部也收拾干净：去掉自绘的假状态栏小图标（系统本来就有真的时间与电量，画两份会露馅），入口图标全部直接摆在桌面上并取消上滑抽屉；顺手删掉三个不再被引用的图标素材 |
| v1.180 | 发布：2026-10-10 21:49:20。桌面放大到整屏（壁纸铺满、状态栏贴顶、三键贴底），中间只留「糯叽」一个大图标、其余页面收进上滑抽屉，Dock 常驻四个常用去处；可换壁纸（6 款内置 + 从相册自选 + 恢复默认）；新增应用商店：可添加自己的安装包直链下载（带进度与完整性校验，坏包不交安装）、扫本机安装包直接装、列出并一键启动手机上已装的应用；三键按真机手感工作；加了壁纸呼吸光、图标依次浮起、点按缩放等动效，轻量档与系统关闭动画时自动静止 |
| v1.179 | 发布：2026-10-10 19:45:49。打开应用先落到一台仿手机的桌面：有机身边框、状态栏（时间 / 日期 / 真实电量 / 信号）、应用图标网格和返回 / 桌面 / 最近三个按键；桌面里只放糯叽自己的入口（糯叽、历史会话、设置、文件、操控台、免费模型、检查更新、新会话），中间卡片会提示当前有没有可聊的模型、点它直接去设置；进去后按手机的返回键或桌面 Home 键回到桌面；设置里可关掉这一层，关掉后打开直接进聊天页 |
| v1.178 | 发布：2026-10-10 19:11:10。颜色换回原来的配色：强调色由系统蓝改回玫红小面积点缀、主体回到墨黑与白（深色模式同步）；发出的气泡恢复黑底白字（深色为白底黑字）、收入恢复白底；卡片 / 胶囊 / 圆牌 / 弹窗重新带发丝描边，页面底恢复冷白渐变；顶部图标与下载进度条回到中性色；苹果风版的排版全部保留（分组列表、气泡收角尾巴、大标题、字号刻度、按下变暗反馈、同一套圆角） |
| v1.177 | 发布：2026-10-10 18:32:33。界面整体换成苹果风：底色改为浅灰分组面、卡片为纯白圆角块、强调色改为系统蓝（深色改为纯黑底 + 深灰卡片）；聊天气泡按 iMessage 重做（发出蓝底白字、收入浅灰底，各收小一角做尾巴）；设置页 / 历史会话 / 文件列表改为分组列表，一组共用一块圆角面板、条目间用缩进发丝细线分隔；顶栏改为大字标题、图标按钮用系统蓝；按钮 / 输入框 / 弹窗 / 进度条统一为 iOS 圆角与留白，取消投影，改为按下变暗；字号与 iOS 对齐（正文与列表标题 17、说明 13、时间戳 11，欢迎页大标题 30）；上一版为投影补的底部留白已退回，卡片内边距重新对称 |
| v1.176 | 发布：2026-10-09 13:13:35。界面整体再打磨一轮：卡片与气泡按真实厚度重新分层（旧版那层投影被面板自己盖住了，等于没生效），边缘补发丝细线与顶缘高光；所有可点区域补齐按下反馈（设置卡片、文件条目、面板功能格、模式胶囊、圆牌按钮、头像、气泡共二十多处），不再有点了没反应的地方；页面底加两片极淡柔光，深色主题单独调过；弹窗、更新弹窗头部、进度条底槽、实心按钮统一换新；文字大小收敛成一套刻度，看不清的 10 号小字全部提到 11 号；顺手清掉三个早就没在用的旧样式文件 |
| v1.175 | 发布：2026-10-08 21:35:45。软件改名：灵犀AI → 糯叽（旧版本与已存配置不受影响，旧安装包照样认得）；操控台的一键任务会先问你要内容（要发的话、要填的表单、要归档的关键词），并新增「不被系统打断」检查与一键放行；文件页的复制到 / 移动到改成直接选去处，可新建文本文件；从文件页交回聊天页的整理任务会自动点亮操控开关 |
| v1.174 | 发布：2026-10-08 20:22:52。新增「文件与文件夹」：建文件夹、改名、复制、挪走、搜索、看剩余空间，删除先进回收站可还原；新增「操控台」：现场实测能不能动手、走哪条路，一键试读屏幕自检，八件常见的事点一下就交给灵犀，随时紧急停止；灵犀从此能真的整理手机里的文件（后台也能干，可在操控台关掉）；下载动画增至 15 款；老手机全面优化（动画共用一条刷新节拍、屏幕外不画、老机器只用省的几个）；更新与下载相关文字全部改成大白话 |
| v1.173 | 发布：2026-10-08 19:00:20。「下载动画」选择页每行右边都是那一款实时跑起来的动画（十款同时跑），点一行放大播一轮、播完自动回到选择页；修掉跑满后 1.1 秒内点「返回选择」会叠两层列表的时机问题 |
| v1.172 | 发布：2026-10-08 18:36:35。「下载动画」预览改成先选后看：10 款选择列表点哪款播哪款，播完一轮自动回到选择列表，播放页可「重播一次 / 返回选择」，中途按返回键直接退出预览 |
| v1.171 | 发布：2026-10-08 18:13:12。下载动画再加 5 款、共 10 款随机（新增吃豆人、小火车、电池充电、火柴人跑步、流星拉尾），「+」面板的「下载动画」预览同步支持连续播放与指定换款 |
| v1.170 | 发布：2026-10-08 13:16:37。聊天页「+」面板新增「下载动画」按钮，可随时预览 5 款下载进度条的动画（满格自动换下一款，也能点「换一款」指定），纯演示不联网 |
| v1.169 | 发布：2026-10-08 13:02:26。下载动画由 2 款增到 5 款（普通横条 / 贪吃蛇 / 像素方块 / 液体波动 / 圆环表盘，每次随机且未被选中的样式不空转）；存档规则改为只有「历史版本」下载才写内部储存「历史记录」，普通更新只留缓存不存档；更新弹窗点「稍后再说」不再追问是否忽略此版本；以前下好但没装的更新包，再点一次直接进安装，不会重复下载；可用的下载线路增到 6 条，某条 10 秒没速度就当场换线 |
| v1.168 | 发布：2026-10-08 12:27:15。下载后不再自动安装，改为存入内部储存「历史记录」并让用户选立即安装/稍后再装，未装版本下次启动提醒一次；新增「免费模型接入」页（复制并打开官网 + 一键把服务地址与模型名存进设置）；浅色配色整体重做（冷白渐变底 + 发丝描边 + 淡投影）；补齐没有模型/没有 Key 时的引导、同一时间只跑一个操控任务、切会话时下载进度归属等逻辑；网络出错提示全部改成中文；备份不再包含 Key 与聊天记录，导出与长图分享路径修好 |
| v1.167 | 发布：2026-10-07 22:32:52。界面整体重做：输入栏只留「+ · 输入框 · 语音 · 发送」，附件/拍照/文件/表情/粘贴图片/导出/联网搜索/手机操控全部收进「+」面板；开启的模式以输入框上方状态胶囊显示，点一下即关；下载或回退后自动把安装包存进内部储存「历史记录」文件夹（Android 11+ 可选「所有文件访问」直接建根目录，否则落 下载/历史记录），设置页新增「历史安装包」区可查、可装、可删；设置页顶部新增三步上手引导；会话列表补新建会话、清空搜索与总数提示；气泡与时间戳排版同步优化 |
| v1.166 | 发布：2026-10-07 21:44:15。新增系统级手机操控：点亮聊天页操控按钮后把任务说出来，AI 自行读屏并点按、滑动、输入完成操作；可选前台（接管屏幕）或后台（不读屏不占屏，只发指令）；操控通道支持无 Root（默认，无障碍）/ Root / Shizuku 三档切换；含急停、默认拦截支付按钮、密码框不回传、步数上限 |
| v1.165 | 发布：2026-10-07 20:15:24。下载进度条新增贪吃蛇彩蛋皮肤：蛇头逐格吃掉豆子推进、带轻微蠕动与满格反馈，每次点击下载在「普通进度条 / 贪吃蛇」间随机二选一；系统动画关闭时自动退回普通样式 |
| v1.164 | 发布：2026-10-07 19:58:19。检查更新全面提速：几条线路同时探测，谁先响应就用谁，不再被慢网络拖住；「历史版本」打开有明显缓存，短时间内重复点开不用等 |
| v1.163 | 发布：2026-10-07 18:58:04。界面全面简约白改造：近白底 + 纯白卡片、用户气泡黑底白字反转、按钮统一黑白反转、设置页分节标题与弹窗圆角重做、全部弹窗改主题感知样式；更新下载改成几条线路同时开跑，先下完并且检查没坏的那条算数，任何一条卡住 15 秒就当场换线，彻底解决「检测到更新后下载慢甚至不下载」 |
| v1.162 | 发布：2026-10-07 17:35:50。生成中的回答点停止会立刻断掉；搜索、语音转文字、检查更新、看历史版本都挪到后台干，界面不再因为等网络卡一下；离开页面后定时动画和草稿保存也跟着停；修好部分手机上从国内来源下载安装包失败、以及老系统装不上的问题 |
| v1.161 | 历史版本列表点整行即可查看该版本完整更新内容（可滚动、可跳转版本页面，弹窗内可直接下载或回退）；把国内更快的来源排在第一位，查更新和下载都优先走它 |
| v1.160 | 换成正式发行版：关掉调试模式，安装包从 6.6MB 压到 2.46MB；连接一律走加密通道；新增会话全量备份与恢复（设置 → 数据备份） |
| v1.159 | 图标去红：桌面图标外圈与自适应图标背景改为中性浅灰描边 + 纯白底；顶栏头像外环同步改为中性描边 |
| v1.158 | 新增国内加速来源：检查更新时两条线路一起测速；下载自动从 6 条线路里挑最快的（国内实测约每秒 2MB） |
| v1.157 | 多条线路一起测速的更新方式；彻底移除公告功能（顶栏铃铛、设置页入口、相关代码与文件） |
| v1.156 | 公告兼容性升级（纯文本公告优先、超长折叠、缺省提示「暂无公告」） |
| v1.155 | 修复新版本下载只走一条慢线路：慢的判定放宽（20 秒还没下完且明显偏慢就换线），备用线路补到 5 条，下载时能看到当前走的是哪条 |
| v1.154 | 整体转简约风：去渐变/光晕/彩色阴影，气泡扁平描边、圆角统一 14dp，进度条纯色，多处图标白圆牌底 |
| v1.153 | 应用图标改为圆形白牌 + 珊瑚描边徽章，消除方角白边 |
| v1.152 | 界面美化：顶栏重做、欢迎页留白、会话卡片强调竖条、设置页分节标题样式 |
| v1.151 | 角色形象头像（桌面 + 顶栏 + 自适应图标）、玫瑰珊瑚主色、动画套件 |
| v1.15 | 按钮统一白底黑字；公告获取提速（短时间内重复打开直接显示，优先走离你最近的线路） |
| v1.14 | 全局主色改为青瓷绿 |
| v1.13 | 历史版本下载；公告系统 |
| v1.12 | 更新页全新重做（版本对比、圆角进度、速度、忽略指定版本） |
| v1.11 | 打字机输出 + 停止生成、日期分隔、搜索来源内联、代码块点击复制、四种导出、草稿保存、会话搜索、表情面板、剪贴板粘贴图片 |
| v1.10 | 顶栏实时时钟 |
| v1.9 | 免费语音识别通道与免费服务预设 |
| v1.8 | 云端 Whisper 语音兜底 + 录音权限 + 语音模型配置 |
| v1.7 | 消息时间戳、文件芯片预览、思考中动画、Markdown 引用块、搜索改用 DuckDuckGo 免 Key |
| v1.6 | 未公开发布（内容并入后续版本） |
| v1.5 | 角色体系、重新生成/编辑重发/删除、导出分享、拍照、语音输入与朗读、浅色主题、字号缩放 |
| v1.1 – v1.4 | 界面美化与已知问题修复 |
| v1.0 | 首个正式版本：云端大模型流式对话、多模型配置、会话历史、图片与文件输入、主题切换、应用内检查更新 |

## 隐私说明

- 模型 API Key 等**全部配置只保存在手机本地**（SharedPreferences），不写入代码、不随安装包分发、不上传任何服务器
- 检查更新和历史版本都不需要注册账号、不绑定手机，也不会把你是谁告诉任何人
- 联网搜索走 DuckDuckGo 公开页面，不需要 Key
- 应用不收集、不上传任何用户数据，无广告、无统计 SDK
- 手机操控：仅在用户点亮操控开关后才读屏；**前台模式**的屏幕文字（以及开启截图时的画面）会发送给用户自己配置的模型服务商，**后台模式**完全不读屏；密码输入框内容一律不回传，支付类按钮默认拦截
- 安装包在三个平台各挂一份（Gitee / GitHub / 只放附件的 CNB 镜像仓），应用内更新会把它们和几个加速站一起竞速，任一家的地址抽风都不影响下载
- 安装包下载完成后自动做完整性校验（大小比对 + 包结构检查），损坏文件不会交给系统安装
- 手指特效：摄像头画面只在手机本机显示、本机识别、本机录制，不上传也不作为任何网络请求发出去；摄像头权限只在点进那一格时才申请，不进去就不会工作；识别文件下载后留在本机，可随时在设置里删掉

## 构建方法

```bash
# 环境：JDK 21、Android SDK（compileSdk 35 / build-tools 35.0.0）、Gradle 8.13
cd LingxiChat
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

构建环境（2026-10-14 起整体搬到 D 盘，C 盘已接近满）：Gradle 8.13 解压在 `D:\gradle-8.13`，官方 `sha256 = 20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`，与 services.gradle.org 的校验值一致（镜像取自 mirrors.cloud.tencent.com/gradle）；`GRADLE_USER_HOME=D:\gradle-home`（依赖与脚本缓存全在 D）；`D:\gradle-home\init.gradle` 沿用原来的国内镜像重定向；`D:\gradle-home\init.d\redirect-build.gradle` 在 `beforeProject` 里把每个工程的 `buildDir` 指到 `D:\nuoji-build\<项目名>`：

```bash
cd /c/Users/Administrator/Desktop/agent/LingxiChat
GRADLE_USER_HOME='D:\gradle-home' /d/gradle-8.13/bin/gradle.bat :app:assembleRelease :app:lintRelease --no-daemon
# 产物：D:\nuoji-build\_app\outputs\apk\release\app-release.apk
```

手指特效（v1.186 起）新增依赖与约束：`com.google.mediapipe:tasks-vision:0.10.26.1`。**版本必须钉在 0.10.26.1，不要顺手升到最新**：v1.192 之前用的 0.10.35，其 `libmediapipe_tasks_jni.so` 引用了 `aligned_alloc`（Android 9 起才有）和 `AHardwareBuffer_*`（Android 8 起才有），而 `System.loadLibrary` 走 `RTLD_NOW`，符号缺失会在加载当场抛 `UnsatisfiedLinkError`——Android 8.x 及以下无论如何都进不了特效页（这正是用户手机上一直报「这份文件读不起来 / 运行库加载不起来」的真正原因，与模型文件无关：官方 `hand_landmarker.task` 逐字节核过，包内那份与 Google 直链的 sha256 一致）。本机用 pyelftools 逐架构扫过：0.10.26.1 与 0.10.21 的原生库没有任何 API>24 符号，0.10.28 起重新引用 `aligned_alloc`，0.10.33 起 `.so` 挪回 tasks-core；所以取「最新的干净版」0.10.26.1（`libmediapipe_tasks_vision_jni.so` 在 tasks-vision 的 `jni/`，只带 arm64-v8a + armeabi-v7a，成品包约 32MB，比 0.10.35 重约 4.7MB）。`defaultConfig.ndk.abiFilters` 仍只留这两个架构；`proguard-rules.pro` 对 `com.google.mediapipe.**` / `org.tensorflow.lite.**` / `com.google.protobuf.**` 整包 keep（C++ 侧按类名反射回调 Java，改名即挂），并对 guava / protobuf / flogger / transport / opentelemetry 补 `dontwarn`。手部识别模型 `hand_landmarker.task`（7,819,105 字节，sha256 `fbc2a300…cde1`，官方直链 `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`）**从 v1.188 起内置在 `app/src/main/assets/`**，`data/EffectModel.kt` 在首次进入特效时从包里放一份到永久目录（有「所有文件访问」落 `/sdcard/特效模型/`，否则落 `getExternalFilesDir(null)/特效模型/`），放完对内容做 sha256 核对，对不上就删掉重放；校验值与 `SIZE` 写在 `EffectModel` 常量里，换模型时两处一起改，且不写进面向用户的文案。`EffectModel` 已无网络下载分支；v1.186 那次挂在 Release 上的模型附件仍不能删（已装 v1.186/v1.187 的手机会去那里下）。

技术栈：Kotlin + Android View（ViewBinding）+ Kotlin 协程 + OkHttp + org.json + MediaPipe Tasks Vision，无第三方 UI 框架。仓库里的 Gradle 使用阿里云镜像加速。

聊天界面异步约定：`lifecycleScope` 管理搜索、语音转写和定时任务；相关阻塞网络请求运行于 `Dispatchers.IO`；流式对话通过 `callbackFlow` 返回，取消收集即中断请求。其他模块仍保留原有异步实现。

## 自测结果

- v1.193 找到「手指特效一直用不了」的真正根因：**不是模型文件**。`libmediapipe_tasks_jni.so`（0.10.35）用 pyelftools 逐架构扫 `.dynsym` 的 `SHN_UNDEF`，确认引用 `aligned_alloc`（Android 9 / API 28 起才有）与 `AHardwareBuffer_*`（Android 8 / API 26），而 `System.loadLibrary` 以 `RTLD_NOW` 加载，符号缺失会在 dlopen 当场抛 `UnsatisfiedLinkError` → Android 8.x 及以下永远进不了特效页（用户看到的就是「这份文件读不起来」，因为 `explain()` 把三类错误都归到同一句文案）。模型侧排除：包内 `assets/hand_landmarker.task` 与 Google 官方直链逐字节一致（7,819,105 字节，sha256 `fbc2a300…cde1`），R8 keep 规则齐、`.so` 未压缩且 4096/16KB 双对齐，都不是问题。
- v1.193 依赖钉到 **0.10.26.1**：0.10.21 / 0.10.26.1 的原生库扫出 post-24 引用为 0，0.10.28 起重新引用 `aligned_alloc` 且额外捆 `libopencv_java4.so`（+13MB），0.10.33 起 `.so` 从 tasks-vision 挪回 tasks-core，故取「最新的干净版」。接口逐项 `javap` 核对：`BaseOptions.Builder.setModelAssetBuffer/setModelAssetPath`、`HandLandmarker.createFromOptions`、`HandLandmarkerOptions.Builder.setNumHands/setMinHandDetectionConfidence/setRunningMode`、`HandLandmarkerResult.landmarks()`、`BitmapImageBuilder(Bitmap)` 全部存在，`detectForVideo` 未变；`.so` 名从 `mediapipe_tasks_jni` 变为 `mediapipe_tasks_vision_jni`，成品包 `classes.dex` 里保留该字符串（1 处，R8 合并了 12 个重复）与 `HandLandmarker` 类。成品 32,059,994 字节（+4.78MB，arm64 `.so` 14,389,600 + v7a 8,320,740），`aapt dump badging` 确认 versionCode 61 / versionName 1.193 / minSdk 24 / native-code arm64-v8a+armeabi-v7a。
- v1.193 加载路径改为两段兜底：先用直接内存 `setModelAssetBuffer`，抛错再用 `setModelAssetPath` 重试，两次都失败才报第一次的错；运行库类失败（`UnsatisfiedLinkError`/`NoClassDefFoundError`/`ExceptionInInitializerError`）不再触发「删掉重放识别文件」，状态卡改成带机型 + Android 版本 + 根因异常（走 cause 链取最里层 message），并支持长按复制到剪贴板
- v1.193 **尚未真机验证**：Android 7/8 需要确认进得去这一页、画面与特效都出来；`hand_landmarker.task`（float16/1）与 0.10.26.1 的图描述兼容性只有真机能证——若仍失败，长按状态卡那行说明复制发回，里面有机型、系统版本和最里层异常
- v1.192 已通过正式签名校验（APK Signature Scheme v2，证书 CN=LingxiAI），release 构建开启代码与资源压缩
- v1.187 成品包 21,423,579 字节（与 v1.186 同尺寸，只换代码）；`aapt2 dump badging` 确认 `launchable-activity = com.lingxi.chat.MainActivity`、label 糯叽、versionCode 54；包内构成：`lib/arm64-v8a/libmediapipe_tasks_jni.so` 10.05MB + `lib/armeabi-v7a/…` 7.06MB（不压缩存储）+ `classes.dex` 1.76MB + `resources.arsc` 0.78MB
- v1.192 安装包扫描未发现内置仓库凭据（成品 27,277,640 字节）（命中项仅为「sk- 开头」这类说明文字与请求头字面量）；`Bearer` 是请求头构造所需的字面量
- 更新下载已重构为几条线路同时开跑：任何一条线路 10 秒没有速度就当场换线，最先下完并通过检查的那条（大小比对 + ZIP 结构 + manifest/dex 校验）才交给安装；本机实测 7 源：Gitee/ghfast/gh-proxy 均 ~2.8s 下完 2.6MB，限流源 1.1s 内即被剔除
- 检查更新与历史版本列表改为并行「先返回先用」：本机实测匿名接口延迟 GitHub 0.8s、Gitee 0.18s，整体检查耗时取决于最快源
- MainActivity 无裸 `Thread` / `runOnUiThread` / `Handler` / `postDelayed`
- v1.177 苹果风改造：`values/colors.xml` 与 `values-night/colors.xml` 全量换成 iOS 系统色板（light: systemGroupedBackground #F2F2F7 / cell #FFF / tint #007AFF / label #1C1C1E；dark: 页面底 #000 / cell #1C1C1E / 气泡 #2C2C2E / tint #0A84FF），token 名与数量两端保持一一对应；21 个 `bg_*` drawable 重写为平面（去掉 layer-list 双层投影与顶缘高光，圆角收敛为 cell 13 / 气泡 18+6 尾巴 / 胶囊 18 / 输入栏 20 / 按钮 12 / 弹窗与抽屉 14），交互层统一保留 `<ripple>` + `@android:id/mask` 做按下变暗；`glow_*`、`card_shadow*`、`card_highlight` 降到全透明保留 token 以免外部引用断裂；新增 `bg_group`（分组面板）与 `bg_icon_tile`（单元格图标底）；`item_file.xml` 重构为「行 + 缩进 58dp 发丝」的分组单元格并挂到 `rvFiles` 的圆角容器上；`values-night/themes.xml` 新建，深色下 `windowLightStatusBar=false`；`code_bg` 由 #EFF0F2 改为 #FFFFFF 以保证代码块在浅灰气泡上仍可辨
- v1.177 已把上一轮为投影内收补的 37 处 `paddingBottom` 退回（17 处整行删除、20 处回值），卡片上下内边距恢复对称
- v1.176 视觉改造清单：29 个 `bg_*` drawable 里重写 19 个（真实投影几何：面板内收 5dp / 阴影层下探，`<ripple>` + `@android:id/mask` 圆角蒙版，`card_shadow` / `card_shadow_near` / `card_highlight` / `glow_accent` / `glow_cool` 五个 token 在 `values` 与 `values-night` 同步新增）；因面板内收 5dp，39 处卡片的 `paddingBottom` 同步 +5dp 补偿；21 处「背景已带 ripple 又叠 `?attr/selectableItemBackground`」的双重反馈删除；删除 0 引用死文件 `bg_tag` / `bg_section_bar` / `bg_search_toggle`
- v1.179 手机桌面：新增 `PhoneActivity` + `activity_phone.xml`（机身 `bg_phone_bezi` / 壁纸 `bg_desk_wall` / 图标底 `bg_desk_tile` / 小组件 `bg_desk_widget` / 三键 `bg_nav_key`，导航与状态栏图标 `ic_nav_*`、`ic_stat_*`），8 个新增 `desk_*` 颜色 token 已同步进 `values` 与 `values-night`（44 / 44 对齐，桌面屏幕在两套主题下都偏深，避免半透明白图标底失去对比）；清单里 LAUNCHER intent-filter 从 `MainActivity` 迁到 `PhoneActivity`，`MainActivity` 改为 `exported="false"`（仅进程内启动，无外部调用方，`onNewIntent` / `applyPrefill` 仍在桌面→聊天路径上生效）；新增 `ConfigStore.homeDesktop`（默认开）与设置页 `swHomeDesktop` 开关；`MainActivity.applyPrefill` 现识别 `new_session` extra 以支持桌面「新会话」图标；时钟用 `View.postDelayed` 自循环并在 `onPause` 撤掉，电量走 `ACTION_BATTERY_CHANGED` 粘性广播只读不注册接收器
- v1.183 手机桌面整体移除：`PhoneActivity.kt` / `activity_phone.xml` / `DeskGlowView.kt`，15 个桌面专用素材（6 张 `bg_wall_*`、`bg_desk_*`、`bg_nav_key`、`bg_input_bar_focused`）、`Theme.LingxiChat.Desk`、清单中的桌面声明、`ConfigStore.wallpaperKey` 与 `deskRecents` 全部删除；12 个 `desk_*` 颜色 token 收敛为 4 个 `glass_*`（`glass_tint` / `glass_top_light` / `glass_edge` / `glass_shade`，浅色白玻璃、深色烟灰玻璃，两端 40 / 40 对齐）；LAUNCHER 回到 `MainActivity`，设置页原桌面入口改为「应用商店与已装应用」
- v1.183 玻璃改为主界面通用：`GlassBackdrop` 改为抓整页根视图（0.3 倍位图 + 两轮可分离盒模糊），抓之前把已注册的玻璃面板临时置 `INVISIBLE` 再还原，避免把上一轮模糊叠进下一轮；`MainActivity` 在 `setContentView` 后 `attach(this, b.root)` 并给 `rvMessages` 挂 `OnScrollListener` 标脏，`onResume` 起 240ms 的 `Runnable`，只在「滚动过 / 正在流式输出」时重抓重绘，`onPause` 撤掉；顶栏与输入栏改成 `GlassSurfaceView`（`app:glassRadius` / `glassBlur` / `glassRim`），输入框焦点由 `setBackgroundResource` 改为 `setGlassFocused`
- v1.183 尚未在设备上安装验证：真机上要看三项——列表快速滚动时倒影的跟手度与功耗（240ms 节流够不够顺）、浅色主题下白玻璃与消息文字的对比、长会话大列表整屏抓图是否掉帧；轻量档走不抓屏的分支
- v1.185 设置页 `activity_settings.xml`：以「空行 + `<View>` 分隔线 6 行 + 12 空格缩进的行容器」为单位整块交换 `swLiquidGlass` / `swEnterSend` / `rowStore` 三行（玻璃开关挪到 主题/字号 之后第一位），改完 minidom 解析通过、`git diff --stat` 26/26 行；第一次尝试把「最近的 `</LinearLayout>`」当行尾，命中了嵌套行的收尾，导致 `模型配置（必做）` 小标题被切进卡片内部——`</LinearLayout>` 总数不变、minidom 照样通过，只有读 diff 才发现结构变了，已 `git checkout --` 回退重做；结论：行尾必须按缩进层级精确匹配
- v1.192 特效页稳定性与录制修正：`AuraField.drawSea` 的粒子池改成按下标遍历并就地替换（原先边迭代 `pool.iterator()` 边 `pool.add()`，ArrayList 的 modCount 一变动下一帧必抛 ConcurrentModificationException，浮层 onDraw 直接崩）；`HandFxActivity.analyzeOnce()` 把 `GlassBackdrop.frameFrom` 挪到 `bmp.recycle()` 之前（原来用的是已回收位图，开玻璃必崩）、整段 `detectForVideo` 移到专用 `handfx-infer` 线程，界面线程只负责 `getBitmap` 和喂玻璃，`inflying` 保证同一时刻只有一张在途，时间戳用 `max(lastTs+1, uptimeMillis)` 保持严格递增；`ClipRecorder.toYuv` 的 V 平面偏移原来是 `w*h + (j/2)*(w/2) + w/2 + i/2`（把下一行 U 的位置当成了 V），正确是 `w*h + (w/2)*(h/2) + (j/2)*(w/2) + i/2`，改完录制片不再发灰偏绿；`rgba`/`plane` 在 `start()` 里一次性分配，去掉每帧 3.7MB 的 `IntArray`；`drain(final=true)` 加 250 次空转上限，编码器不出 EOS 时不再钉死录影线程；`stop()` 返回是否真写出轨道，配合文件大小校验决定要不要交给相册；`onDestroy` 把编码器收尾和 `HandLandmarker.close()` 分别 post 到各自线程队尾再 `quitSafely()`，`push()` 的 PTS 改由 `ClipRecorder` 按实际入队帧号自算。`EffectModel.writableDir` 去掉 `getExternalFilesDir(null)!!` （外部储存返回 null 时空指针），改为 `ownDir()` 退回 `filesDir`，`remove()` 清掉所有候选位置而不是只删第一个。新增「拍一张」：`shoot()` 抓 1080 宽合成帧、临时 `AuraField` 先空跑 8 帧把 `shown` 缓推到满强度再画，JPEG 92 经 MediaStore 落 `Pictures/糯叽`（Q 以下走公共目录，再不行落应用自己目录并报出路径）。
- v1.191 手指特效页的真玻璃：`GlassBackdrop` 新增「相机帧模式」——`attachFrame(activity, texCam)` 登记提供画面的视图作为背景基准，`frameFrom(activity, bmp)` 由 `HandFxActivity.analyzeOnce()` 每帧把已经取到的相机小图（480 宽，顺手复用，不额外抓一帧）画进 `cap` 走同一套盒模糊；缩放仍固定 `SCALE=0.3`（与抓视图树那条路一致），面板改用 `scaleOf(activity)` 换算要贴的区块，两套来源共用一份绘制代码。相机帧不含玻璃自身，这条路上不需要「抓之前把面板藏掉」；`refresh()` 在帧模式直接返回，绝不重画视图树。`DevicePerf.lowEnd` 或关掉液态玻璃时 `glassFeed=false`，一帧都不喂，面板退回半透明纯色底。
- v1.190 新增国内镜像：CNB 公开仓 `tuui.rrj/lingxi-chat-release` **只放发布版安装包附件**（README + 每版一个 tag + `lingxi-vX.Y.apk`，源码一个字节都不进，源码仍只推 Gitee/GitHub）。建仓 `POST https://api.cnb.cool/{namespace}/-/repos`；发版三步：git 推 tag → `POST /-/releases` `{tag_name,name,body,ref}` → `asset-upload-url` / PUT 原字节 / 确认接口（201/200/200）。匿名直链 `https://cnb.cool/tuui.rrj/lingxi-chat-release/-/releases/download/{tag}/{file}` 实测 200 全量、SHA 与本地一致，且它回 206 支持分段。App 侧统一在 `net/Mirrors.kt`：`forAsset(gitee, github, cnb, extra)`，代理站 7 个；`UpdateUi.mirrorCandidates` 用 `Mirrors.CNB_FROM="1.190"` 门控，老版本不去撞不存在的地址；`StoreActivity.routes()` 只对 `github.com/.../releases/download/` 形式的用户链接展开加速线路，其它站点原样一条。
- v1.189 `FastFetcher.fetch()` 结构改正：整包那条腿用 `sources` 直接构造 `Probe` 立刻启动，**探测结果不再决定某条线能不能用**（探测只用于挑出支持 Range 的线路和拿总大小），探测放进独立线程、总预算 5 秒（原来是逐条 `join(8_000)`，八条线最坏能耗 64 秒才开始下），探不到支持分段就安静退出；`probe()` 里去掉 `if (p.size > 0) out.add(p)` 这个丢线路的条件，改成每条线一律收录。`wholeRace` 的完成判定优先用响应自己的 `Content-Length`（`ownLen`），只有线路不自报长度时才回退到调用方给的期望大小。`UpdateUi` 两处下载回调失败分支把原因写回 `status` 并保持进度区可见（原来只有一条 Toast，用户只看见卡在「正在同时连几条线路…」）。
- v1.188 识别文件改内置：`app/src/main/assets/hand_landmarker.task`（7,819,105 字节，打包后 5.58MB，成品 27,277,492 字节）；`EffectModel.ensure()` 去掉网络分支，改成「本机永久目录里有且内容核对通过就用 → 否则从 assets 复制到 `.new` 临时名、核对通过再改名」（改名不成的机型整份拷一次），目录优先级仍是 有「所有文件访问」→ `/sdcard/特效模型/`，否则 `getExternalFilesDir(null)/特效模型/`；`HandFxActivity` 状态卡与设置页文案从「下载」改成「放一份 / 重放一份」，读失败仍是第一次自动重放、第二次才让用户点。注意：v1.186 挂在 Release 上的模型附件不要删——已装 v1.186/v1.187 的手机还会去那儿下，只有 v1.188 起才是内置。
- v1.187 下载改道 `net/FastFetcher.kt`（更新包、识别文件、应用商店下载安装共用）：先对每条线发 `Range: bytes=0-1` 探测——回 206 的才算支持分段；支持分段的切成 ≥256KB 的段、4 条连接同时抓，某段失败换线重抓那一段；不支持分段的（实测 Gitee 附件最终落 `foruda.gitee.com`，带 Range 也回 200 整包）走整包竞速，12 秒无进账即 cancel 该线；两条腿各写 `.seg.dl` / `.one.dl` 临时文件，先通过大小校验的一方 `renameTo` 交付、另一方删除，`cleanup()` 兜底扫残留。本机对 7.46MB 实测：Gitee 单线 1.44MB/s、gh-proxy 单线 1.29MB/s、gh-proxy 四段 1.52MB/s，另一轮四段只跑出 0.51MB/s——本机出口抖动比分段收益更大，所以不拿这组数字当倍率承诺，改动拿到的是两条结构性收益：任何一条线半死都不会吊住整次下载、支持分段的线路可以多连接叠带宽。
- v1.186 手指特效实现：`SegmentBar` 继承 `GlassSurfaceView`（因此把该类改成 `open`），选中胶囊在 `dispatchDraw` 之前画、由 `ValueAnimator` 推 `pos` 滑动，染色底 `chip_active_bg` + `accent_soft` 描边 + `accent` 粗体字，不用实心块以免压掉玻璃；`AuraField` 把 21 个归一化关键点 → 张合度 = 均值(指尖到腕 / 指根到腕)，<0.18 判握拳、>0.55 判张开，粒子与石面只画进调用方给的 Canvas，所以屏幕浮层与录制合成共用同一份代码，粒子池按 `DevicePerf.lowEnd` 降到 90

- v1.186 摄像头与推理：Camera2 只挂预览一路输出到 TextureView，`applyTransform` = 先按摄像头原始尺寸还原比例 → 转 `SENSOR_ORIENTATION` → 等比放大铺满；识别帧取 `TextureView.getBitmap(480, h)`（480 宽在 1080p 屏上是 3 倍降采样，实测够认手又不至于拖住界面线程）；`BaseOptions.Builder` 只有 `setModelAssetPath/FileDescriptor/Buffer`，**没有 `setModelAssetFile`**，所以下载来的文件要读进 `ByteBuffer.allocateDirect(size)` 再用 `createFromOptions(..., RunningMode.VIDEO)`；`FileChannel.read(buffer)` 是循环读、返回值 0 或 -1 都要当结束处理（`allocateDirect(0)` 会抛 `IllegalArgumentException`）
- v1.186 录制：`ClipRecorder` 自己做 RGBA→YUV420Planar（BT.601 全量程）喂 `MediaCodec` H.264 + `MediaMuxer`；两个 API 坑：`configure(fmt, null, flag)` 有重载歧义要写 `null as Surface?`，`MediaMuxer` 收尾是 `stop()` 不是 `finish()`；`HandLandmarkerResult` 没有 `close()`。抓帧只能在界面线程（`grabTick` 在 `b.root` 上 50ms 一跳），画特效与编码在 `recHandler`；录制**必须另起一份 `AuraField`** 并用 `@Volatile` 关键点快照喂它——和屏幕上那份共用粒子池会让两条线程同时改同一个 `ArrayList`（`ConcurrentModificationException`）；`stopRecord()` 把 `recorder.stop()` 也 post 回 `recHandler`，让收尾和最后一帧串行；成品先落 `cacheDir/record`，再按版本发 MediaStore（Q+ 走 `IS_PENDING`）或公共 `Movies/糯叽` + `MediaScannerConnection`；上限 20 秒自动停
- v1.192 尚未在设备上安装验证：这一页还要看两项——顶栏/底栏的模糊是否跟着相机画面实时变化（识别取帧约 15fps，玻璃就最多这个更新率）、开玻璃后这一页的发热与耗电；
- v1.186 尚未在设备上安装验证，真机要看六项：预览方向与「铺满」换算（后置镜头 + `screenOrientation="portrait"`）、`TextureView.getBitmap()` 是否已把 transform 烘进位图（没烘到则关键点与特效会错位）、CPU 手部推理的实际帧率与发热、录制成品在相册里的可播性与色彩偏差、`hand_landmarker.task` 五条下载线的可用性（`EffectModel.RELEASE_TAG=v1.186` 的附件，务必与本次 Release 同号）、以及 32 位老机器上 `armeabi-v7a` 那份 so 能否正常加载；轻量档（核数 ≤4）粒子减半，特效强度需肉眼再评
- v1.184 闪退定位与修复：`GlassSurfaceView.onDraw` 里的 `GlassBackdrop.snapshot()` 会在缓存为空时当场 `refresh(force=true)`，也就是在本次硬件绘制的调用栈里再 `root.draw(软件 Canvas)` 重入同一棵视图树——v1.183 的一打开即崩即来源于此。现在 `snapshot()` 退成纯读取，抓屏只允许 `MainActivity.glassTick`（`postDelayed` 240ms）与滚动标脏触发，且抓前先判 `isAttachedToWindow` 与 `windowVisibility`；`cap` / `blur` / `px` / `tmp` 四块缓冲按尺寸复用（v1.183 的 `boxBlur` 每帧 `src.copy()` 出新的 `blur` 却从不回收上一张，长时间聊天只会涨不会降）；抓屏包在 `try/catch(Throwable)` 内并计入 `fails`，连续两次即 `dead=true`、释放缓冲并把 `ConfigStore.liquidGlass` 写回 false，本机永久退回半透明底；面板 `onDraw` 的贴图段同样包 `try/finally`，用 `saves` 计数保证 canvas 栈平衡；`onDestroy` 补 `removeCallbacks(glassTick)` 与 `GlassBackdrop.detach(this)`（旧写法按 `activity.hashCode()` 存 `State` 且从不删，退出过一次聊天页的那两张位图加两条 `IntArray` 会一直留在进程里）
- v1.182 液态玻璃实现：`GlassBackdrop`（把 `deskWall` + `glowDesk` 两个源视图按 `getLocationInWindow` 的相对坐标抓成 0.5 倍 ARGB_8888 小图，按半径缓存两轮可分离盒模糊）+ `GlassSurfaceView`（`LinearLayout` 子类 + `setWillNotDraw(false)`；圆角裁剪后贴模糊图 → 靠边 9dp 用 `INVERSE_EVEN_ODD` 环带把背景按 1.09 倍重绘模拟折射 → `LinearGradient` 镜面高光与 0.9dp 描边 → 下沿 12dp 内阴影 → 按压值驱动圆角 +12%、底色加深、径向高光跟随手指，松手 `ValueAnimator(from→1.18→0)` 过冲回弹）；`res/values/attrs.xml` 新增 `GlassSurface`（`glassRadius` / `glassBlur` / `glassTint` / `glassRim`）；`ConfigStore.liquidGlass`（默认开）+ 设置页 `swLiquidGlass`；`DevicePerf.lowEnd` 或关掉开关时不抓背景，只画 tint 与描边
- v1.182 编译踩坑（第一轮才暴露，之前误报过构建通过）：`View.getLocationInWindow` 只收 `IntArray`；`View.draw(Canvas, Paint)` 不是公开 API，只能 `draw(Canvas)`；`Bitmap.copyTo` 不存在，改用 `src.copy(Bitmap.Config.ARGB_8888, false)`；另外 Gradle 脚本缓存残留会让构建 16 秒内挂在「Could not move temporary workspace … groovy-dsl」，删掉 `D:\gradle-home\caches\8.13\groovy-dsl` 即恢复
- v1.182 磁盘现状：C 盘仅剩约 540MB（`app/build` 已删、产物改落 D），D 盘装完依赖后剩约 2.6GB；下次构建前建议先删 `D:\gradle-8.13-bin.zip`（130MB）与 `D:
uoji-build\_app\intermediates` 里的旧中间产物
- v1.181 入口回退：`AndroidManifest` 的 LAUNCHER intent-filter 从 `PhoneActivity` 迁回 `MainActivity`（`exported="true"`），`PhoneActivity` 改回 `exported="false"` 且删掉「未开启桌面就转发到聊天页」的自转发逻辑，入口由 `SettingsActivity` 的 `rowDesk` 行提供；`ConfigStore.homeDesktop` 与 `KEY_HOME_DESKTOP` 一并删除（不再有这个开关），`activity_settings.xml` 里那行 SwitchCompat 换成带 chevron 的入口行；`activity_phone.xml` 删掉自绘状态栏行（`tvDeskClock` / 三个 `ic_stat_*` / `tvDeskBattery`）与抽屉面板与把手，电量并进时钟小组件的日期行（`PhoneActivity.battery`），入口网格 `llDeskGrid` 由 `buildDeskGrid()` 按 `entries` 每行 4 个生成，`deskTile()` 长按「换壁纸」格子也能直接换；`ic_stat_battery`/`ic_stat_signal`/`ic_stat_wifi` 三个素材随假状态栏一起删除
- v1.181 构建验证：`assembleRelease` + `lintRelease` 无 Error/Fatal；全部 layout / values XML 逐文件 parse；`aapt2 dump badging` 确认 `launchable-activity = com.lingxi.chat.MainActivity`、label 糯叽、49 / 1.181
- v1.181 尚未在设备上安装验证：桌面页现在只能通过设置进入，需确认真机上进出该页与聊天页的返回栈是否正常（桌面里点「糯叽」进聊天后按返回应回到桌面，再按返回退出应用），以及删除自绘状态栏后各厂商机型的系统状态栏在深色壁纸上的可读性
- v1.180 桌面与商店：`PhoneActivity` 改为全屏桌面（`deskWall` 壁纸层 + `DeskGlowView` 呼吸光 + 上滑抽屉 + 多任务浮层，图标由代码排 3 列网格），新增 `StoreActivity` + `activity_store.xml`（可下载应用 / 本机安装包 / 已装应用三段）；`ConfigStore` 新增 `wallpaperKey` 与 `storeApps`（JSON 数组）；`UpdateUi` 开放 `installFile(activity, apk)` 与 `checkApk(apk, expectedSize)`，商店因此复用更新流程里同一套「授权引导 + 调起安装」和「大小 / ZIP 结构 / manifest / dex」三重校验，不另写一份；6 个 `bg_wall_*` 作为画面素材固定深色（保证桌面白字对比，不跟主题走），`Theme.LingxiChat.Desk` 的 `windowBackground` 与默认壁纸统一成 `bg_wall_crimson` 并删掉过渡用的 `bg_desk_wall`；`AndroidManifest` 注册 `StoreActivity`（exported=false）；自定义壁纸按屏宽 2 倍采样存 `filesDir/wallpaper/custom.img`
- v1.180 构建坑：新增 `StoreActivity` 时漏写 `import com.lingxi.chat.databinding.ActivityStoreBinding`，Kotlin 报的是一串「Unresolved reference 'llInstalled' / 'btnBack'」而不是「找不到 binding」，容易误判成 ViewBinding 没生成、白清一次构建缓存；另一处是 `lifecycleScope.launch { val x = withContext(IO) { … } }` 之后误用 `return@withContext`，应为 `return@launch`
- v1.179 静态校验：`lintRelease` 首轮报 13 个 `UseAppTint` Error（ImageView 上用 `android:tint`），已加 `xmlns:app` 并改为 `app:tint` 后重跑通过；全部 layout / drawable / values 逐文件 parse 通过；`aapt2 dump badging` 确认 `launchable-activity: com.lingxi.chat.PhoneActivity`、label 糯叽、47 / 1.179
- v1.178 静态校验（配色回退）：`values/colors.xml` 与 `values-night/colors.xml` 用 `git show HEAD~1:` 整体退回 v1.176 的玫红黑白色板，并删除此轮引入后已零引用的 7 个 token（`card_shadow` / `card_shadow_near` / `card_highlight` / `glow_accent` / `glow_cool` / `surface_dim` / `accent_deep`），两端 token 数量仍一一对应（36 / 36）；21 个 `bg_*` 保留 v1.177 的 iOS 几何（cell 13 / 气泡 18+6 尾巴 / 胶囊 18 / 输入栏 20 / 按钮 12 / 弹窗与抽屉 14）与 `<ripple>` 按压层，只把填充与描边语义换回白面 + `card_stroke` 发丝、用户气泡恢复 `user_bubble_start→end` 墨黑渐变、进度条填充换回 `btn_solid` 中性、`bg_app` 恢复 `bg_top→bg_bottom` 冷白渐变（不带柔光）
- v1.177 静态校验：全部 layout / drawable / values 逐文件 XML parse 通过，并额外扫描「同一元素重复属性」（首轮曾把 `android:letterSpacing` 与 `android:textSize` 各写重一次，已合并）；`assembleRelease` 与 `lintRelease` 无 Error/Fatal；`aapt2 dump badging` 确认 `application-label:'糯叽'`、`com.lingxi.chat`、45 / 1.177
- v1.180 尚未在设备上安装验证：抽屉的上滑手势与系统手势条会不会冲突、长按桌面会不会误触发换壁纸、大图壁纸的内存占用、慢网下商店下载的超时表现，以及「已安装的应用」在各厂商 ROM 上能被看到多少，都需真机确认
- v1.179 尚未在设备上安装验证：桌面布局在不同屏宽 / 刘海 / 手势条上的适配（尤其是机身与三键在窄屏会不会挤压图标网格）、点图标进聊天后返回是否落回桌面、关掉桌面开关后是否直达聊天页，都要真机确认；`overridePendingTransition` 在 Android 14 上已废弃，若转场异常改为删除该调用即可
- v1.178 尚未在设备上安装验证：本轮只做配色回退，排版 / 圆角 / 分组列表沿用 v1.177；白面对白底页面在低亮度屏上的区分度、深色模式下白底黑字气泡的可读性需真机目测
- v1.177 尚未在设备上安装验证：本轮只换视觉语言（配色 / 圆角 / 气泡 / 分组列表 / 字号），未动任何业务逻辑与接口；iOS 色板在真机屏上的对比度、蓝底白字气泡的可读性、分组容器圆角与列表滚动的贴合度都需真机目测。回退方式：revert 本轮提交即可整体退回 v1.176 的黑白极简风；颜色 token 未增删（仅新增 `bg_group` / `bg_icon_tile` 两个 drawable），不会留下悬空引用
- 此前已发布版本的安装流程已完成验证
- 完整走通：检查更新 → 多条线路一起测速 → 下载（自动换线路 + 检查包是否完整）→ 授权安装 → 安装后版本号正确
- 微信/QQ 无关功能不涉及；深浅主题、字号缩放、回车发送、表情面板、剪贴板图片均已验证
- Gitee 公开仓匿名可读（仓库 API、Releases API、APK 直链均 200）

## 推广

[维云模型开放平台](https://vsllm.cc/v1)：一站式大模型开发与应用平台，提供兼容 OpenAI 格式的模型 API。在糯叽的「模型配置」里把接口地址填 `https://vsllm.cc/v1` 即可直接使用。

## 免责声明

本项目为个人开源工具，API Key 由使用者自行填写并自行承担相应服务商的计费与使用责任；任何模型服务的中断、限流、内容政策均由其服务商决定，与本项目无关。
手机操控由用户自行开启并作用于用户自己的设备，用自动化方式操作第三方应用可能不符合该应用的用户协议，相关后果由使用者自行承担。