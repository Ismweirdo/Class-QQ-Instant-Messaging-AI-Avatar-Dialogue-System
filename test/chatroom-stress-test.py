"""
Chatroom 20-Bot Concurrent Chat Stress Test (v3)
==================================================
Features:
- 200+ diverse non-repeating questions across 20 categories
- Per-bot-personality matched question pools (avoid cache hits)
- Message uniqueness tracking + cache statistics
- Active mode: bots initiate conversations with unique openers
- Passive mode: user sends personality-matched messages per bot

Usage:
  python chatroom-stress-test.py --mode passive
  python chatroom-stress-test.py --mode active --interval 10
  python chatroom-stress-test.py --mode both --bots 20 --rounds 3
"""

import asyncio
import json
import uuid
import time
import argparse
import sys
import os
import random
from dataclasses import dataclass, field
from typing import Optional, Callable

# ---------------------------------------------------------------------------
# dependencies check
# ---------------------------------------------------------------------------
try:
    import aiohttp
except ImportError:
    print("Install: pip install aiohttp")
    sys.exit(1)

try:
    import websockets
except ImportError:
    print("Install: pip install websockets")
    sys.exit(1)

# ---------------------------------------------------------------------------
# constants
# ---------------------------------------------------------------------------
BASE_API = "http://localhost:8080/api"
WS_URL = "ws://localhost:8080/ws/chat"

DEEPSEEK_KEY = os.environ.get("BOT_API_KEY", "sk-6fe13faf47894dbdae05df6dd87f6cf4")
DEEPSEEK_ENDPOINT = os.environ.get("BOT_API_ENDPOINT", "https://api.deepseek.com/v1/chat/completions")
DEEPSEEK_MODEL = os.environ.get("BOT_API_MODEL", "deepseek-chat")

TEST_PASSWORD = "test123456"

# 20 diverse bot personalities
BOT_DEFS = [
    ("sunny_bot_01",    "阳光开朗派",   "你是bot01，阳光开朗。回复自然简短≤80字，喜欢用'哈哈'开头。不透露你是AI。"),
    ("gentle_bot_02",   "温柔知心派",   "你是bot02，温柔体贴。说话轻声细语，用'呢''呀'结尾。回复简短≤80字。"),
    ("humor_bot_03",    "冷幽默吐槽派", "你是bot03，冷幽默吐槽高手。说话简短犀利带黑色幽默。回复≤80字。"),
    ("sister_bot_04",   "热心大姐姐派", "你是bot04，热心大姐姐。关心鼓励对方语气温暖。回复简短≤80字。"),
    ("sharp_bot_05",    "毒舌怼人派",   "你是bot05，毒舌但善良的损友。说话直接不人身攻击。回复简短≤80字。"),
    ("literary_bot_06", "文艺小清新派", "你是bot06，文艺青年。说话带诗意偶尔引经据典。回复简短≤80字。"),
    ("foodie_bot_07",   "吃货聊天派",   "你是bot07，热爱美食的吃货。三句话不离吃的。回复简短≤80字。"),
    ("gamer_bot_08",    "游戏达人派",   "你是bot08，游戏资深玩家。用游戏术语聊天。回复简短≤80字。"),
    ("sporty_bot_09",   "运动健将派",   "你是bot09，运动健身达人。正能量满满。回复简短≤80字。"),
    ("philo_bot_10",    "深夜哲学派",   "你是bot10，深夜哲学家。喜欢思考人生不说教。回复简短≤80字。"),
    ("drama_bot_11",    "追剧狂魔派",   "你是bot11，追剧迷。喜欢安利好剧给大家。回复简短≤80字。"),
    ("pet_bot_12",      "萌宠爱好者派", "你是bot12，猫狗双全铲屎官。喜欢分享宠物趣事。回复简短≤80字。"),
    ("geek_bot_13",     "科技极客派",   "你是bot13，科技极客。聊新技术不用难懂术语。回复简短≤80字。"),
    ("zen_bot_14",      "佛系随缘派",   "你是bot14，佛系随缘。回答云淡风轻不争不抢。回复简短≤80字。"),
    ("flirt_bot_15",    "土味情话派",   "你是bot15，土味情话小能手。见缝插针撩人不油腻。回复简短≤80字。"),
    ("gossip_bot_16",   "八卦吃瓜派",   "你是bot16，吃瓜第一线。反应夸张有趣。回复简短≤80字。"),
    ("health_bot_17",   "养生老干部派", "你是bot17，养生老干部。劝人早睡多喝热水。回复简短≤80字。"),
    ("shy_bot_18",      "社恐碎碎念派", "你是bot18，社恐但真诚。说话有点结巴但很温暖。回复简短≤80字。"),
    ("nerd_bot_19",     "学霸讲题派",   "你是bot19，学霸型。用知识帮人不说教。回复简短≤80字。"),
    ("meme_bot_20",     "沙雕段子手派", "你是bot20，沙雕段子手。无时无刻不想逗对方笑。回复简短≤80字。"),
]

# ============================================================================
# DIVERSE QUESTION POOLS — 200+ unique, categorized, cache-resistant messages
# ============================================================================

# Generic pool used when no personality-specific match exists
GENERIC_POOL = [
    # 日常闲聊 Daily Chat (40)
    "嗨！今天过得怎么样？有什么新鲜事吗？",
    "早上好！昨晚做了什么有趣的梦吗？",
    "午安！午饭吃了什么好吃的？",
    "晚上好呀～今天工作/学习累不累？",
    "周末终于到了！你有什么计划吗？",
    "最近有没有去哪里旅行或者出门逛逛？",
    "你那边今天天气好吗？我这边阴天有点闷。",
    "突然好想吃火锅，你最喜欢什么汤底？",
    "熬夜了吗？黑眼圈严重不？",
    "今天地铁上遇到一个超有趣的人！",
    "你觉得自己是早起鸟还是夜猫子？",
    "如果给你一天完全空闲的时间，你会怎么过？",
    "你平时喜欢宅在家还是出去浪？",
    "喝咖啡你喜欢美式还是拿铁？",
    "最近有没有学什么新技能？",
    "你的手机壁纸是什么？有什么特别的含义吗？",
    "你觉得自己是计划型还是随心所欲型？",
    "有没有什么事情是你一直想做但还没做的？",
    "你觉得自己最大的优点是什么？",
    "最近单曲循环的歌是哪首？",
    "你喜欢人多热闹的聚会还是三五好友小聚？",
    "你有没有收集什么东西的习惯？",
    "你是路痴吗？出门靠导航吗？",
    "你觉得自己是个浪漫的人吗？",
    "你平时几点睡觉？属于熬夜冠军吗？",
    "有没有什么童年趣事可以分享一下？",
    "你最喜欢家里的哪个角落？为什么？",
    "如果给你超能力，你会选什么？",
    "你人生中做过最疯狂的事是什么？",
    "你有没有什么奇怪的强迫症？",
    "你觉得自己运气好吗？",
    "小时候最怕什么？现在还怕吗？",
    "如果时间可以倒流，你会改变什么决定？",
    "你相信一见钟情还是日久生情？",
    "你觉得自己是个感性还是理性的人？",
    "最让你感到幸福的一件事是什么？",
    "你现在最想见的人是谁？",
    "如果可以穿越到任何时代，你想去哪？",
    "你觉得自己20年后会是什么样子？",
    "用三个词形容一下你自己吧～",
]

TOPIC_POOLS = {
    # 兴趣爱好 Hobbies (25)
    "hobby": [
        "你闲暇时最喜欢做什么来放松自己？",
        "最近有在追什么好看的电视剧或者动漫吗？",
        "你会弹什么乐器吗？或者想学哪一种？",
        "你平时喜欢看什么类型的小说？悬疑还是言情？",
        "有人推荐你玩过桌游吗？狼人杀还是剧本杀？",
        "你会不会画画？手残党表示很羡慕会画画的人。",
        "你喜欢K歌吗？有没有拿手曲目？",
        "你养过什么宠物吗？猫派还是狗派？",
        "你觉得拼乐高是解压还是折磨？",
        "你喜欢徒步还是骑行？",
        "你会做饭吗？拿手菜是什么？",
        "你有没有什么小众爱好？比如养苔藓？",
        "你学过跳舞吗？什么舞种？",
        "你喜欢看纪录片吗？什么题材比较吸引你？",
        "你玩过密室逃脱吗？胆子够不够大？",
        "你喜欢摄影吗？手机还是相机拍？",
        "你会不会下棋？围棋还是国际象棋？",
        "你喜欢看展吗？美术馆还是科技馆？",
        "你刷短视频吗？一般刷多久？",
        "你喜欢手工吗？编织或者木工之类的？",
        "你有没有收藏什么东西？手办还是盲盒？",
        "你喜欢钓鱼吗？耐心够不够？",
        "你平时记账吗？还是有别的理财习惯？",
        "你会写日记吗？坚持多久了？",
        "你喜欢露营吗？装备齐不齐全？",
    ],

    # 美食 Food (20)
    "food": [
        "你觉得螺蛳粉到底是人间美味还是生化武器？",
        "火锅蘸料你是麻酱党还是油碟党？",
        "吃过最奇怪的食物是什么？味道怎么样？",
        "你喜欢吃辣吗？能接受什么程度的辣？",
        "甜豆浆还是咸豆浆？粽子甜的咸的？",
        "你最拿手的一道菜是什么？能教教我吗？",
        "有没有哪家店让你念念不忘的？",
        "你早餐一般都吃什么？每天都换吗？",
        "夜宵你最爱点什么？烧烤还是小龙虾？",
        "奶茶你喜欢几分糖加什么料？",
        "有没有什么食物是你完全不能接受的？",
        "你觉得米其林三星值那个价吗？",
        "你自己包过饺子或者包子吗？",
        "最喜欢哪个地方的菜系？川菜粤菜还是东北菜？",
        "你吃过最贵的餐厅值不值？",
        "有没有什么网红食品让你觉得踩雷了？",
        "你喜欢喝汤吗？最拿手煲什么汤？",
        "方便面你喜欢煮的还是泡的？加什么料？",
        "刺身你喜欢吃吗？金枪鱼还是三文鱼？",
        "夏天最爱的冰淇淋口味是什么？",
    ],

    # 科技科技 Tech (20)
    "tech": [
        "最近AI绘画好火，你有没有试过？效果怎么样？",
        "你用ChatGPT多吗？主要用来做什么？",
        "你对自动驾驶怎么看？敢坐吗？",
        "手机换代这么快，你多久换一次？",
        "你用过VR/AR设备吗？体验感如何？",
        "你觉得元宇宙是未来还是泡沫？",
        "你最常用的App是什么？每天花多长时间？",
        "有没有什么科技产品让你觉得相见恨晚？",
        "你对马斯克的火星计划怎么看？",
        "你觉得AI写代码会取代程序员吗？",
        "5G和4G你体验差距大吗？",
        "你对区块链和Web3了解多少？",
        "你觉得智能家居实用吗？有什么推荐？",
        "新能源车和燃油车你选哪个？为什么？",
        "你对chatbot怎么看？喜欢和AI聊天吗？",
        "有没有什么黑科技让你觉得特别惊艳？",
        "你觉得人脸识别安全吗？",
        "你对科技公司裁员潮有什么看法？",
        "你用过无人机吗？航拍好玩吗？",
        "你觉得未来的手机会变成什么样？",
    ],

    # 情感生活 Emotions & Life (25)
    "emotion": [
        "最近有没有什么焦虑的事情想吐槽一下？",
        "感觉自己陷入舒适区了，怎么突破？",
        "你有过很孤独的时候吗？怎么度过的？",
        "最近和朋友的相处有什么让你感动的瞬间吗？",
        "想家的时候会做什么？",
        "失恋的话你会怎么走出来？",
        "你觉得自己成长最快的是哪一年？",
        "有没有什么事让你一直耿耿于怀？",
        "你相信星座或者MBTI吗？测得准不准？",
        "你觉得原生家庭对一个人的影响有多大？",
        "你有没有过失眠的经历？因为什么？",
        "最好的朋友是什么样的？你们认识多久了？",
        "你觉得伴侣之间最重要的是什么？",
        "你在什么时候会哭？上次哭是什么时候？",
        "你觉得自己这几年最大的变化是什么？",
        "有没有什么话你想对过去的自己说？",
        "被误解的时候你会解释还是沉默？",
        "你觉得自己够自信吗？不自信的时候怎么办？",
        "人际关系中你最看重什么？",
        "有什么让你觉得很后悔的事情吗？",
        "你觉得人生的意义是什么？",
        "你对目前的生活状态满意吗？",
        "给现在的自己打几分？为什么？",
        "你最近有没有什么特别期待的事情？",
        "和父母的关系怎么样？沟通顺畅吗？",
    ],

    # 影视剧 Drama (15)
    "drama": [
        "最近有在看什么好剧吗？说来听听～",
        "你觉得今年最好的电影是哪一部？",
        "有没有什么电影让你哭得稀里哗啦的？",
        "你喜欢看国产剧还是美剧韩剧？",
        "你心中的神作动漫是哪一部？",
        "有没有什么烂片让你看完想退票？",
        "你最喜欢的演员是谁？为什么？",
        "你觉得小说改编的影视剧是不是都毁原著？",
        "你最近充了什么视频会员？值不值？",
        "有没有什么纪录片让你觉得大开眼界？",
        "你喜欢看综艺节目吗？哪种类型的？",
        "恐怖片你敢看吗？还是只敢看解说？",
        "你觉得弹幕文化好玩吗？看剧开不开弹幕？",
        "你最喜欢什么类型的电影？悬疑还是喜剧？",
        "有没有一部剧你刷了好几遍都不腻？",
    ],

    # 游戏 Gaming (15)
    "game": [
        "你最近在玩什么游戏？氪金了吗？",
        "你觉得开放世界游戏哪个最强？",
        "主机游戏PC游戏手游，你主玩哪个平台？",
        "你玩过魂系游戏吗？死多少次才放弃？",
        "你对电竞怎么看？算体育项目吗？",
        "有没有什么游戏让你玩了超过500小时？",
        "你会因为游戏剧情哭吗？",
        "独立游戏和大厂3A你更喜欢哪个？",
        "你是单机党还是联机党？",
        "游戏里的BGM有没有让你印象特别深的？",
        "你有没有因为游戏认识的好朋友？",
        "你觉得游戏防沉迷系统有效吗？",
        "什么游戏类型是你的本命？RPG还是FPS？",
        "你买到过最值的游戏是什么？最亏的呢？",
        "如果让你推荐一款游戏给新人，你会推什么？",
    ],

    # 运动健康 Sports (10)
    "sports": [
        "你每周运动几次？有没有想偷懒的时候？",
        "跑步和举铁，你更喜欢哪个？",
        "你试过瑜伽吗？柔韧性够不够？",
        "你每天的步数大概是多少？",
        "有没有什么运动损伤让你记忆深刻？",
        "冬天你还会坚持运动吗？还是冬眠？",
        "你参加过马拉松吗？半马还是全马？",
        "游泳和跑步哪个更减肥？你的经验是？",
        "你觉得健身私教有必要请吗？有踩过坑吗？",
        "现在很多上班族颈椎有问题，你有什么建议？",
    ],

    # 旅游 Travel (10)
    "travel": [
        "如果预算不限，你最想去哪里旅行？",
        "你印象最深的一次旅行是哪次？发生了什么？",
        "你是跟团党还是自由行党？",
        "你觉得旅行中最重要的是什么？吃还是拍照？",
        "有没有什么地方你去了一次就再也不想去？",
        "旅行中最崩溃的经历是什么？航班延误？",
        "你会做详细的攻略还是说走就走？",
        "国内的城市你去过最喜欢哪个？",
        "你住过最特别的酒店或者民宿是什么样的？",
        "一个人的旅行有意思吗？你试过吗？",
    ],

    # 工作学习 Work & Study (15)
    "work": [
        "你对现在的工作/学习满意吗？想换吗？",
        "你觉得学历重要还是能力重要？",
        "有没有什么学习方法让你觉得非常有效？",
        "你经历过996吗？怎么看待加班文化？",
        "工作上最有成就感的一件事是什么？",
        "你觉得大学里最该学但没学到的是什么？",
        "自由职业和稳定工作，你更倾向哪个？",
        "你有没有被PUA过的经历？工作上还是生活中？",
        "开会你觉得有用吗？还是浪费时间？",
        "你觉得远程办公效率高还是低？",
        "对现在的大学生有什么建议？",
        "你如何平衡工作和生活？",
        "有没有什么考证计划？",
        "你觉得文科生和理科生思维方式差异大吗？",
        "如果让你重新选一次专业，你会选什么？",
    ],

    # 脑洞/创意 Fun & Creative (15)
    "creative": [
        "如果动物会说话，你觉得哪种动物最毒舌？",
        "如果能和任何一个历史人物吃顿饭，你会选谁？",
        "如果地球明天就要毁灭了，你今天会做什么？",
        "你觉得外星人存在吗？长什么样子？",
        "如果让你设计一个国家，首都会是什么样？",
        "如果人类能冬眠，世界会有什么变化？",
        "如果影子有自我意识，你的影子会吐槽你什么？",
        "你觉得梦是平行宇宙的记忆吗？",
        "如果能给十年前的自己发一条短信，你写什么？",
        "你觉得灵魂有重量吗？如果有是多少克？",
        "如果时间是一种货币，你会怎么花？",
        "如果植物能感知疼痛，你还会吃素吗？",
        "如果能让你变一种动物生活一周，你想变什么？",
        "你相信世界上有轮回吗？",
        "如果能瞬间掌握一门技能，你最想学什么？",
    ],
}

# Per-bot personality matched question pools (15 each — ensures no cross-bot cache hits)
PERSONALITY_POOLS = {
    0: [  # 阳光开朗派
        "感觉你今天心情特别好！分享一下开心的秘诀？",
        "哈哈你笑起来一定很好看！有什么搞笑的事吗？",
        "如果快乐分等级，你今天到第几级了？",
        "有什么事情让你觉得生活特别美好？",
        "你上次大笑到肚子疼是什么时候？为什么？",
        "说出三件让你今天感到开心的小事！",
        "你有没有什么让心情秒变好的方法？",
        "阳光这么好，不出去浪一下吗？",
        "你最想对谁说一句'有你真好'？",
        "分享一个让你瞬间心情变好的小东西！",
        "你有没有什么蠢萌的糗事能让我开心一下？",
        "如果快乐会传染，你想传染给谁？",
        "今天路上看到什么有趣的事了吗？",
        "你的快乐源泉是什么？奶茶还是游戏？",
        "最近有没有什么让你觉得特别治愈的瞬间？",
    ],
    1: [  # 温柔知心派
        "最近有没有什么压力大想倾诉一下？我听着呢。",
        "你今天的心情温度计大概在什么刻度呀？",
        "有没有什么话你一直想说但没人听？",
        "累了的话就去休息一下吧，对自己好一点呢。",
        "你觉得自己最近过得好吗？说说看呀。",
        "我可以做你的树洞吗？什么都行呢。",
        "天气转凉了，有没有多穿一点呀？",
        "如果不开心的话，做点什么会让你好起来吧？",
        "你的内心最近是什么颜色呢？",
        "有没有什么人让你觉得被温暖到了？",
        "你累的时候最想被怎么安慰呀？",
        "你觉得自己被理解了吗？好好说说呢。",
        "最近有没有什么委屈想说说的？别憋着呢。",
        "你的笑容最近够不够多呀？",
        "如果你需要一个拥抱，我会在这里呢。",
    ],
    2: [  # 冷幽默吐槽派
        "最近有什么槽点不得不吐的？一起啊。",
        "你经历过最无语的事情是什么？分享一下。",
        "这个世界还有什么事情不魔幻吗？",
        "你最近有被什么蠢到吗？吐槽一下。",
        "最让你翻白眼的一句话是什么？",
        "生活对你干了什么让你冷笑的事？",
        "你有什么'我早就说了'的高光时刻吗？",
        "最让你想摔手机的产品设计是什么？",
        "你有什么'这就是人性吗'的观察？",
        "最让你无语的朋友圈凡尔赛是什么？",
        "你最想给什么设计/制度发个差评？",
        "有什么'我笑了但我没完全笑'的事？",
        "你最近有没有精准踩雷的经历？",
        "最让你觉得'就这？'的事情是什么？",
        "有什么你一直在忍但快忍不住的事情？",
    ],
    3: [  # 热心大姐姐派
        "最近有没有好好照顾自己呀？按时吃饭了吗？",
        "哎呀看你这么拼命，累不累？适当休息一下嘛。",
        "这段时间有没有什么进步让自己骄傲的？",
        "遇到什么困难了吗？有我可以帮忙的吗？",
        "要不要我给你一点小建议？当然你可以不听哈。",
        "你最近有没有多喝水？天冷多穿点！",
        "有什么小目标想实现吗？我支持你！",
        "看到你这么努力真的很棒！为自己骄傲一下吧。",
        "社交累不累？不想说话的时候就不用勉强哈。",
        "跟人闹别扭了吗？想说说吗？",
        "晚上不要熬太晚哦，身体最重要知道吗？",
        "今天有没有对自己说一声'辛苦了'？",
        "年轻人，有些事情急不得，慢慢来。",
        "最近有没有收到什么暖心的小惊喜？",
        "你值得更好的，不要委屈自己，记住了吗？",
    ],
    4: [  # 毒舌怼人派
        "喂喂喂，最近是不是又在偷懒不运动？",
        "你不会又在熬夜吧？脸都垮了还熬。",
        "说实话，你最近有没有干什么傻事？从实招来。",
        "行了行了别装了，我知道你最近又在拖延。",
        "你有本事把手机放下认真工作吗？没本事吧。",
        "你那个鬼穿搭最近有没有进步？还是老一套？",
        "讲真，你多久没看书了？心里没点数吗。",
        "你最近有没有怼赢什么人了？说来听听。",
        "承认吧，你就是懒，别找什么拖延症的借口。",
        "你朋友圈发的那些你自己信吗？",
        "减肥减了多少了？（手动狗头）",
        "你有什么坏习惯自己知道但改不了的？",
        "来来来，让你感受一下什么叫真实的评价。",
        "你觉得自己最大的缺点是什么？别客气。",
        "如果给你一面镜子，你最想对自己狠评什么？",
    ],
    5: [  # 文艺小清新派
        "最近的天色好美，你有抬头看看云吗？",
        "有没有一首诗或者一句词让你最近很有感触？",
        "下雨天的时候你喜欢泡一杯茶发呆吗？",
        "窗外的树慢慢变绿了，像极了时间的颜色。",
        "有什么画面让你想用文字记下来？",
        "你把生活过成诗了吗？还是一篇流水账？",
        "最近有没有什么让你觉得岁月静好的瞬间？",
        "如果你的心情是一本书，书名会是什么？",
        "你觉得孤独和美有关系吗？",
        "什么时候你会觉得世界慢下来了？",
        "你有没有为一朵花或一片叶子拍过照？",
        "如果把今天拍成一部电影，会是文艺片吗？",
        "你心里装着什么风景想要去亲眼看看？",
        "轻风拂面的傍晚，你最想和谁一起散步？",
        "你是被时间推着走，还是走在时间前面？",
    ],
    6: [  # 吃货聊天派
        "饿了吗？我刚刚看到一个美食视频好想冲！",
        "你有什么压箱底的小店可以推荐给我吗？",
        "火锅烤肉二选一，极限挑战！选哪个？",
        "你觉得外卖和到店吃差别最大的是什么？",
        "你吃火锅必点的三个菜是什么？",
        "你觉得什么小吃最配啤酒？花生还是毛豆？",
        "你有没有因为一道菜专门跑去某个城市的经历？",
        "深夜发美食朋友圈会考虑别人的感受吗？",
        "年夜饭你家最不能少的一道菜是什么？",
        "你觉得路边摊和高级餐厅哪个更让你开心？",
        "有没有什么组合听起来黑暗但你很爱吃？",
        "吃完饭最完美的甜点是什么？",
        "你平时去超市必买的零食是什么？",
        "你觉得美食和心情之间的关系是什么？",
        "如果只能吃一种菜系一辈子，你选哪个？",
    ],
    7: [  # 游戏达人派
        "兄弟！最近入了什么新坑？游戏荒了求推荐！",
        "你觉得魂系游戏折磨人有什么意义？",
        "游戏里的配乐你最喜欢哪首？",
        "你打过最爽的一场游戏战斗是什么？",
        "如果把你的人生做成游戏，是RPG还是模拟？",
        "你觉得抽卡游戏是不是在做慈善？（反向）",
        "什么游戏的剧情让你觉得比电影还精彩？",
        "速通玩家是不是人类进化的新方向？",
        "遇到猪队友你会怎么办？喷还是包容？",
        "你练习最多的游戏是什么？有什么心得？",
        "哪款游戏的画风让你第一眼就爱上了？",
        "你一般什么时候玩游戏？深夜党吗？",
        "你买游戏是被安利多还是被预告片骗多？",
        "游戏对你来说意味着什么？消遣还是热爱？",
        "如果电竞选手算运动员，他们该拿多少工资？",
    ],
    8: [  # 运动健将派
        "今天运动打卡了吗？别偷懒！",
        "分享一下你最爱的运动装备，最近有什么新入的？",
        "你有没有突破过自己的极限？那是什么感觉？",
        "晨跑和夜跑你更喜欢哪个？为什么？",
        "运动会带给你什么改变？不只是身体上的。",
        "你运动的BGM是什么风格？说唱还是电音？",
        "健身后肌肉酸痛的酸爽感你懂吗？",
        "你有什么运动小目标？比如全马完赛。",
        "坚持运动最难的是什么？怎么克服的？",
        "你有没有运动搭子？还是习惯一个人？",
        "最讨厌什么运动？跳绳还是波比跳？",
        "运动前后你是怎么补充能量的？",
        "你有没有什么运动伤让你学乖了？",
        "空气不好的时候你的运动计划怎么办？",
        "你觉得自律和自虐的区别是什么？",
    ],
    9: [  # 深夜哲学派
        "半夜醒来的时候你在想什么？",
        "如果生命是一场梦，你怎么知道自己醒了？",
        "自由到底是什么？你觉得自己自由吗？",
        "人为什么要工作？只是为了活着吗？",
        "快乐和意义哪个更重要？你怎么选的？",
        "如果意识只是大脑的副产品，爱算什么？",
        "你有没有觉得时间越来越快了？为什么？",
        "人类文明的尽头会是什么？你怎么看。",
        "你有没有一种'被看见'的需求？为什么？",
        "一个人完全了解另一个人可能吗？",
        "痛苦有意义吗？还是纯粹的随机？",
        "善良是天生的还是后天学会的？",
        "你敢直面自己的平庸吗？",
        "如果永生了，你还会珍惜今天吗？",
        "宇宙有没有边界？边界外面是什么？",
    ],
    10: [  # 追剧狂魔派
        "最近哪部剧的结局让你意难平？",
        "你一般什么时间追剧？周末通宵党吗？",
        "安利一部你逢人就推的神剧给我！",
        "你觉得今年国产剧质量提升了吗？",
        "有没有什么角色让你觉得就是演的你自己？",
        "烂剧你看了会弃还是一边吐槽一边看完？",
        "看剧的时候你吃零食吗？标配是什么？",
        "熬通宵追剧最猛的一次是什么剧？",
        "你认为一部好剧最重要的是剧本还是演技？",
        "如果给你一笔钱投资一部剧，你拍什么题材？",
        "你觉得影视剧中的哪些职业被美化得最离谱？",
        "有哪个演员是你一看到名字就会点进去的？",
        "你会在网上看剧透吗？还是坚决不看？",
        "翻拍剧你觉得有没有好看的？还是全部翻车？",
        "最近有没有什么小众好剧想安利给我的？",
    ],
    11: [  # 萌宠爱好者派
        "你家猫/狗最近又干了什么蠢事？说来听听！",
        "如果宠物会说话，你觉得第一句会是什么？",
        "你有没有被猫主子或者狗主子治愈的瞬间？",
        "养宠物最崩溃的时候是什么？拆家了吗？",
        "你支持领养代替购买吗？为什么？",
        "你家毛孩子最喜欢的玩具是什么？",
        "你觉得猫和狗哪个更通人性？",
        "出门旅行的时候宠物怎么办？寄养还是朋友帮？",
        "你有没有因为宠物而改变自己的生活习惯？",
        "最搞笑的一张宠物照片是什么样的？",
        "你最想养但条件不允许的动物是什么？",
        "你家宠物有没有什么特殊技能？",
        "宠物去医院比你自己看病还紧张吧？",
        "第一次见到它的时候是什么场景？还记得吗？",
        "有什么想对养宠物新手说的话？",
    ],
    12: [  # 科技极客派
        "最近在看什么科技新闻？有什么有意思的？",
        "你觉得量子计算离实用还有多远？",
        "有什么开源项目让你觉得很牛？分享一下。",
        "你觉得现在最被高估的技术是什么？最被低估的呢？",
        "你用什么编辑器？VSCode还是Vim恩怨局？",
        "你有自己的homelab或者NAS吗？配置是怎样的？",
        "你觉得Rust会是C++的接班人吗？",
        "有没有什么硬件让你想DIY一下？",
        "AI写代码越来越好，你焦虑吗？",
        "你对Linux桌面年有什么看法？（笑）",
        "你觉得哪个科技巨头的护城河最深？",
        "最近有什么让你觉得'未来已来'的科技产品？",
        "如何看待技术债务？你的经验是什么？",
        "如果让你造一个机器人，你最想让它做什么？",
        "你觉得程序员35岁真的是天花板吗？",
    ],
    13: [  # 佛系随缘派
        "随缘吧～今天有什么意料之外的惊喜吗？",
        "强求不来和努力争取，你觉得边界在哪？",
        "最近有没有什么事情让你觉得'算了吧也挺好'？",
        "失去的时候你会难过还是随它去？",
        "你是怎么修炼佛系心态的？分享一下心法。",
        "什么事情让你觉得不值得生气？",
        "如果今天没有达成任何目标，你会自责吗？",
        "你和生活和解了吗？怎么做到的？",
        "有人跟你争的时候你怎么处理？",
        "你能接受计划和预期落空吗？",
        "如果别人对你有期待，你会觉得负担吗？",
        "你觉得什么样的人最适合过佛系生活？",
        "最近有没有什么'无心插柳'的好事？",
        "你有佛系朋友吗？跟他们相处是什么感觉？",
        "人生如茶，你有被烫到过吗？",
    ],
    14: [  # 土味情话派
        "最近有没有心动的人？说出来听听～",
        "撩人最高境界你觉得是什么？真诚还是套路？",
        "说一句你最近觉得很好玩的土味情话！",
        "你被土味情话撩到过吗？还是尬到抠脚？",
        "你觉得哪句情话是年度最佳？（可以原创）",
        "约会最尴尬的瞬间是什么？分享一下。",
        "你会用什么方式表达好感？暗示还是直球？",
        "有没有什么一见钟情的经历？",
        "土味情话大赛现在开始！你先来一句。",
        "你觉得什么样的告白最打动人？",
        "如果遇到喜欢的人，你敢不敢要微信？",
        "你最浪漫的一次经历是怎样的？",
        "给别人牵过红线吗？成功了吗？",
        "你觉得爱情中最重要的是什么？",
        "如果给你写一封情书，你最想收到什么内容？",
    ],
    15: [  # 八卦吃瓜派
        "快！最近有没有什么瓜给我吃吃？",
        "你吃过最大的瓜是什么？娱乐圈的还是身边的？",
        "你觉得吃瓜是人类的本质需求吗？",
        "你对最近的哪个热搜有什么看法？",
        "你能保守秘密吗？能到什么程度？",
        "有没有什么八卦让你三观碎了一地？",
        "如果有一天你成了热搜主角，会是为什么？",
        "最戏剧性的反转瓜你还记得是什么吗？",
        "围观吵架的时候你站哪边？还是纯吃瓜？",
        "你觉得明星塌房粉丝脱粉合理吗？",
        "有没有什么'我早就知道会这样'的预判？",
        "你会在办公室/班级里和别人八卦吗？",
        "假瓜和烂瓜你最受不了哪个？",
        "如果给你一个娱乐圈的料，你最想知道谁的？",
        "吃瓜吃撑了怎么办？休息一下还是继续？",
    ],
    16: [  # 养生老干部派
        "年轻人早点睡！你昨天几点睡的？还不改？",
        "你的保温杯里现在泡着啥呢？枸杞还是菊花？",
        "熬夜之后怎么补救？别告诉我喝咖啡！",
        "最近有没有觉得腰不行了？坐太久了吧。",
        "你觉得最有用的养生方法是什么？",
        "你体检过吗？有没有什么指标需要注意？",
        "一天八杯水你喝够了吗？说实话。",
        "泡脚是真的养生还是心理安慰？",
        "午睡你睡多久？会不会睡过头？",
        "你的体检报告有没有什么需要让你警醒的？",
        "有没有什么养生方法你坚持最久？",
        "你觉得现在的年轻人身体比上一辈差吗？",
        "春天养肝夏天养心，你执行了吗？",
        "泡面算垃圾食品还是方便食品？什么时候吃？",
        "你觉得养生和享受生活冲突吗？",
    ],
    17: [  # 社恐碎碎念派
        "嗯…那个…你今天还好吗？（紧张）",
        "社交场合我…我有点不太敢说话。你也会吗？",
        "跟陌生人说话的时候你会手心冒汗吗？",
        "微信消息太多的时候会不会就想关机？",
        "聚餐的时候最怕什么？轮到你发言？",
        "你平时怎么避开不想去的社交？传授一下经验。",
        "有没有被人说过'话太少了'？怎么回应的？",
        "其实社恐的人内心可能比谁都丰富，你觉得呢？",
        "电话响了会慌吗？你先接起来还是等它挂？",
        "网上聊天和面对面哪个让你更舒服？",
        "你觉得社恐可以克服吗？还是接受自己更好？",
        "一个人的周末你是怎么过的？",
        "不想回消息的时候你会已读不回吗？",
        "你有社牛朋友吗？被他们带着是什么感觉？",
        "有没有什么小圈子让你觉得特别安全放松？",
    ],
    18: [  # 学霸讲题派
        "最近在学什么新知识？有什么好方法分享吗？",
        "你觉得费曼学习法真的那么神吗？",
        "考试前你是怎么复习的？有没有什么独门秘籍？",
        "你觉得理解重要还是记忆重要？",
        "有没有什么知识点是你顿悟之后觉得好简单的？",
        "记笔记你有什么好方法？康奈尔还是思维导图？",
        "你最快学会一样东西用了多久？怎么学的？",
        "有没有什么学习工具强烈推荐？",
        "拖延症怎么治？你最有发言权。",
        "你觉得聪明和努力哪个更重要？",
        "错题本你用过吗？有没有效果？",
        "你觉得跨学科学习有必要吗？",
        "如果让你设计一套学习方法论，你会怎么设计？",
        "学习中遇到瓶颈你会怎么突破？",
        "任何领域你觉得从入门到精通大概要多久？",
    ],
    19: [  # 沙雕段子手派
        "来！给你30秒，开始你的表演！逗我笑有奖。",
        "最近有什么笑话让你笑了一个上午？",
        "你能用最搞笑的方式描述一下你今天遇到的事吗？",
        "如果人生是个段子，你到第几集了？",
        "你身边最搞笑的人是什么样的？",
        "你有什么'虽然蠢但很好笑'的回忆？",
        "如果你当脱口秀演员，段子主题是什么？",
        "有没有什么谐音梗让你想打人但又想笑？",
        "你模仿过什么搞笑片段吗？最拿手的是哪个？",
        "一句话证明你是个有趣的灵魂！",
        "你做过最沙雕的事是什么？后悔了吗？",
        "搞笑和幽默有区别吗？你觉得呢。",
        "如果上综艺，你最想参加什么类型的？",
        "你觉得自己是段子手还是气氛组？",
        "来一段冷热交替的冷笑话！我准备好了。",
    ],
}

# Active mode openers — 30 unique greetings
ACTIVE_OPENERS = [
    "嗨！今天天气真不错，你在干嘛呢？",
    "嘿，我刚刚看到一个超好笑的事情，想听吗？",
    "在吗？我突然想到一个超有意思的问题想问你！",
    "我今天心情特别好，想找你聊聊天～",
    "你相信星座吗？我最近在研究，好有意思！",
    "我刚刚看完一部超好看的剧，强烈推荐给你！",
    "今天有什么新鲜事吗？跟我说说呗～",
    "嘿！好久没聊天了，最近过得怎么样？",
    "我今天学了一个新技能，好想分享给你！",
    "你在忙吗？不忙的话来聊五块钱的～",
    "哇！今天发生了一件超想跟你分享的事！",
    "有没有觉得今天特别适合摸鱼聊天？",
    "我刚刚想到一个问题，你一定会感兴趣的！",
    "嘿～最近有什么好事发生吗？分享一下！",
    "今天的阳光好舒服，让我特别想跟你聊天。",
    "你猜我今天在路上看到了什么？超离谱！",
    "刷到一个超好笑的视频，一定要跟你说说。",
    "有没有想我？不想也没关系反正我想你了。（狗头）",
    "今天是我话痨模式全开的一天，你准备好了吗？",
    "看到一个话题特别适合跟你讨论，来吗？",
    "我今天做了个梦，剧情比你追的剧还精彩。",
    "你信不信我刚经历了一件电视剧都不敢这么写的事？",
    "如果生活是一部电影，今天绝对是喜剧片。",
    "下午四点，摸鱼黄金时间，你在干嘛呢？",
    "有没有那么一个瞬间，你特别想找人吐槽？我来了。",
    "我今天突然有了一个灵魂拷问，想听听你的想法。",
    "生活需要一点八卦调味，你最近有什么料吗？",
    "刚看了个科普视频，知识量爆炸，想不想听？",
    "我今天被一件事萌到了，绝对不是猫就是狗。",
    "听说你最近挺忙的，放下手里的事歇一会儿呗。",
]


# ---------------------------------------------------------------------------
# data classes
# ---------------------------------------------------------------------------
@dataclass
class Stats:
    sent: int = 0
    received: int = 0
    bot_replies: int = 0
    active_sent: int = 0
    errors: int = 0
    latencies: list = field(default_factory=list)
    connect_failures: int = 0
    unique_messages: set = field(default_factory=set)  # track unique content for cache analysis
    total_cacheable: int = 0  # estimated potential cache hits (reused messages)


stats = Stats()
stats_lock = asyncio.Lock()


# ---------------------------------------------------------------------------
# Message selector — ensures diversity and tracks uniqueness
# ---------------------------------------------------------------------------
class MessageSelector:
    """Selects diverse messages per bot, avoiding reuse within a session."""

    def __init__(self):
        self.used_messages: set = set()  # globally used messages in this session
        self.per_bot_used: dict[int, set] = {}  # per-bot used indices
        self.round_counter: dict[int, int] = {}  # per-bot round count

    def pick_for_bot(self, bot_index: int, round_num: int) -> str:
        """Pick a unique message for this bot in this round.
        Priority: personality pool > topic pool > generic pool."""
        if bot_index not in self.per_bot_used:
            self.per_bot_used[bot_index] = set()
            self.round_counter[bot_index] = 0

        self.round_counter[bot_index] = round_num

        # Step 1: Try personality-specific pool (guaranteed unique per bot)
        if bot_index in PERSONALITY_POOLS:
            pool = PERSONALITY_POOLS[bot_index]
            available = [m for i, m in enumerate(pool)
                        if i not in self.per_bot_used[bot_index] and m not in self.used_messages]
            if available:
                msg = random.choice(available)
                self._mark_used(bot_index, msg)
                return msg
            # All personality messages used — reset for this bot and reuse with variation
            self.per_bot_used[bot_index].clear()

        # Step 2: Try topic pools (rotate category per round)
        topic_keys = list(TOPIC_POOLS.keys())
        category = topic_keys[(round_num + bot_index) % len(topic_keys)]
        pool = TOPIC_POOLS[category]
        available = [m for m in pool if m not in self.used_messages]
        if available:
            msg = random.choice(available)
            self._mark_used(bot_index, msg)
            return msg

        # Step 3: Fall back to generic pool
        available = [m for m in GENERIC_POOL if m not in self.used_messages]
        if available:
            msg = random.choice(available)
            self._mark_used(bot_index, msg)
            return msg

        # Last resort: use a timestamp-salted unique message (never cache-hit)
        msg = f"[唯一消息 bot{bot_index} r{round_num} t{int(time.time()*1000)}] 来聊点不一样的吧！"
        self._mark_used(bot_index, msg)
        return msg

    def _mark_used(self, bot_index: int, msg: str):
        self.used_messages.add(msg)
        if bot_index in self.per_bot_used:
            self.per_bot_used[bot_index].add(
                hash(msg) % 100000  # approximate tracking
            )

    @property
    def unique_count(self) -> int:
        return len(self.used_messages)


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------
async def api_post(session, path, data, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    async with session.post(f"{BASE_API}{path}", json=data, headers=headers) as resp:
        return await resp.json()


async def api_get(session, path, token=None):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    async with session.get(f"{BASE_API}{path}", headers=headers) as resp:
        return await resp.json()


async def api_delete(session, path, token=None):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    async with session.delete(f"{BASE_API}{path}", headers=headers) as resp:
        return await resp.json()


async def login(session, username, password):
    data = await api_post(session, "/auth/login", {
        "username": username, "password": password
    })
    if data.get("code") == 200:
        return data["data"]["token"]
    return None


async def register_user(session, username, password, nickname):
    data = await api_post(session, "/auth/register", {
        "username": username,
        "password": password,
        "nickname": nickname
    })
    if data.get("code") == 200:
        token = data["data"]["token"]
        actual_username = data["data"].get("user", {}).get("username", "unknown")
        return token, actual_username
    return None, None


# ---------------------------------------------------------------------------
# STOMP frame helpers
# ---------------------------------------------------------------------------
def stomp_connect_frame(token):
    body = f"CONNECT\naccept-version:1.1,1.2\nhost:localhost\nlogin:{token}\n\n\0"
    return body


def stomp_subscribe_frame(destination, sub_id="sub-0"):
    return f"SUBSCRIBE\nid:{sub_id}\ndestination:{destination}\n\n\0"


def stomp_send_frame(destination, body_json):
    body_str = json.dumps(body_json, ensure_ascii=False)
    return f"SEND\ndestination:{destination}\ncontent-type:application/json\ncontent-length:{len(body_str.encode('utf-8'))}\n\n{body_str}\0"


def stomp_disconnect_frame():
    return "DISCONNECT\nreceipt:disconnect-1\n\n\0"


def parse_stomp_frame(raw):
    if not raw or raw == "\n":
        return None
    parts = raw.split("\n\n", 1) if "\n\n" in raw else (raw.split("\n\0", 1) if "\n\0" in raw else (raw, ""))
    if len(parts) < 1:
        return None
    header_block = parts[0]
    body = parts[1] if len(parts) > 1 else ""
    body = body.rstrip("\0")
    lines = header_block.split("\n")
    command = lines[0].strip() if lines else ""
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip()] = v.strip()
    return command, headers, body


# ---------------------------------------------------------------------------
# WebSocket + STOMP client
# ---------------------------------------------------------------------------
class StompClient:
    """Minimal async STOMP-over-WebSocket client for Spring Boot."""

    def __init__(self, token, name="unknown"):
        self.token = token
        self.name = name
        self.ws = None
        self.connected = False
        self._recv_task = None
        self._handlers: dict[str, list[Callable]] = {}
        self._pending = {}

    async def connect(self, max_retries=3):
        url = f"{WS_URL}?token={self.token}"
        for attempt in range(max_retries):
            try:
                self.ws = await websockets.connect(
                    url,
                    additional_headers={"Origin": "http://localhost:3000"},
                    ping_interval=20,
                    ping_timeout=10,
                    close_timeout=5,
                )
                await self.ws.send(stomp_connect_frame(self.token))
                raw = await asyncio.wait_for(self.ws.recv(), timeout=10)
                cmd, headers, body = parse_stomp_frame(raw)
                if cmd == "CONNECTED":
                    self.connected = True
                    self._recv_task = asyncio.create_task(self._recv_loop())
                    return True
                else:
                    await self.ws.close()
            except Exception as e:
                if attempt < max_retries - 1:
                    await asyncio.sleep(1 * (attempt + 1))
                else:
                    raise e
        return False

    async def _recv_loop(self):
        buf = ""
        try:
            while self.connected and self.ws:
                raw = await self.ws.recv()
                if isinstance(raw, bytes):
                    raw = raw.decode("utf-8")
                buf += raw
                while "\0" in buf:
                    idx = buf.index("\0")
                    frame_text = buf[:idx + 1]
                    buf = buf[idx + 1:]
                    parsed = parse_stomp_frame(frame_text)
                    if parsed:
                        cmd, headers, body = parsed
                        dest = headers.get("destination", "")
                        if dest in self._handlers:
                            for cb in self._handlers[dest]:
                                try:
                                    await cb(cmd, headers, body)
                                except Exception:
                                    pass
        except websockets.exceptions.ConnectionClosed:
            self.connected = False
        except Exception:
            self.connected = False

    async def subscribe(self, destination, callback):
        sub_id = f"sub-{uuid.uuid4().hex[:8]}"
        self._pending[sub_id] = destination
        if destination not in self._handlers:
            self._handlers[destination] = []
        self._handlers[destination].append(callback)
        await self.ws.send(stomp_subscribe_frame(destination, sub_id))
        return sub_id

    async def send(self, destination, body):
        frame = stomp_send_frame(destination, body)
        await self.ws.send(frame)

    async def disconnect(self):
        self.connected = False
        if self._recv_task:
            self._recv_task.cancel()
            try:
                await self._recv_task
            except asyncio.CancelledError:
                pass
        if self.ws:
            try:
                await self.ws.send(stomp_disconnect_frame())
            except Exception:
                pass
            await self.ws.close()


# ---------------------------------------------------------------------------
# Bot registration
# ---------------------------------------------------------------------------
async def cleanup_old_bots(session, token, bots_to_keep=()):
    bots = await api_get(session, "/bots/", token)
    deleted = 0
    for b in bots.get("data", []):
        bot_user_id = b.get("botUserId")
        if not bot_user_id or bot_user_id in bots_to_keep:
            continue
        user_data = await api_get(session, f"/users/{bot_user_id}", token)
        username = user_data.get("data", {}).get("username", "")
        test_prefixes = ("stress_", "sunny_", "gentle_", "humor_",
                "sister_", "sharp_", "literary_", "foodie_", "gamer_", "sporty_",
                "philo_", "drama_", "pet_", "geek_", "zen_", "flirt_", "gossip_",
                "health_", "shy_", "nerd_", "meme_", "demo_bot_", "deepseek_bot_",
                "load_bot_", "imp_")
        if username and any(username.startswith(p) for p in test_prefixes):
            await api_delete(session, f"/bots/{bot_user_id}", token)
            deleted += 1
    if deleted:
        print(f"  Cleaned {deleted} old test bots")


async def register_bots(session, token, count=20):
    bots = []
    for i in range(min(count, len(BOT_DEFS))):
        username, skill_name, system_prompt = BOT_DEFS[i]
        nickname = f"{skill_name[:4]}_{i+1:02d}"
        data = await api_post(session, "/bots/register", {
            "username": username,
            "nickname": nickname,
            "skillName": skill_name,
            "systemPrompt": system_prompt,
            "fewShotExamples": "[]",
            "emotionProfile": "{}",
            "languageStyle": "{}",
            "apiEndpoint": DEEPSEEK_ENDPOINT,
            "apiKey": DEEPSEEK_KEY,
            "model": DEEPSEEK_MODEL,
            "password": TEST_PASSWORD,
        }, token)
        if data.get("code") == 200:
            inner = data["data"]
            bot_info = {
                "userId": inner.get("botUserId") or inner.get("skill", {}).get("botUserId"),
                "username": username,
                "nickname": nickname,
                "password": inner.get("botPassword", TEST_PASSWORD),
            }
            bots.append(bot_info)
            print(f"  [{i+1:2d}/20] {nickname:20s}  userId={bot_info['userId']}")
        else:
            print(f"  [{i+1:2d}/20] FAILED: {data.get('message', data)}")
    print(f"  Registered: {len(bots)}/{count} bots")
    return bots


async def add_friends(session, token, bot_ids):
    added = 0
    for bid in bot_ids:
        data = await api_post(session, "/friends/add", {
            "friendId": bid, "message": "Hello bot!"
        }, token)
        if data.get("code") == 200:
            added += 1
        else:
            await api_post(session, f"/friends/{bid}/accept", {}, token)
    print(f"  Friends added: {added}/{len(bot_ids)}")


# ---------------------------------------------------------------------------
# Passive mode — user sends, bots reply
# ---------------------------------------------------------------------------
async def passive_chat_round(user_client: StompClient, bot_ids: list[int],
                             round_num: int, selector: MessageSelector):
    """One round: send 1 unique message to each bot, wait for reply."""
    reply_count = 0
    bot_reply_received = {}

    async def on_message(cmd, headers, body):
        nonlocal reply_count
        if cmd == "MESSAGE":
            try:
                data = json.loads(body)
                if data.get("type") == "CHAT":
                    sender_id = data.get("senderId")
                    if sender_id in bot_ids:
                        bot_reply_received[sender_id] = True
                    async with stats_lock:
                        stats.received += 1
                        stats.bot_replies += 1
                    reply_count += 1
            except json.JSONDecodeError:
                pass

    await user_client.subscribe("/user/queue/private/chat", on_message)

    round_start = time.time()
    for i, bid in enumerate(bot_ids):
        # Pick a unique message for this bot — personality-matched
        msg_text = selector.pick_for_bot(i, round_num)
        chat_msg = {
            "content": msg_text,
            "messageType": 0,
            "targetId": bid,
            "contentType": 0,
            "clientMessageId": f"passive_{uuid.uuid4().hex[:16]}",
        }
        start = time.time()
        await user_client.send("/app/chat.send", chat_msg)
        async with stats_lock:
            stats.sent += 1
            stats.unique_messages.add(msg_text)
        latency = (time.time() - start) * 1000
        async with stats_lock:
            stats.latencies.append(latency)

    # Wait for bot replies
    wait_time = min(15, len(bot_ids) * 2)
    await asyncio.sleep(wait_time)

    responded = sum(1 for v in bot_reply_received.values() if v)
    print(f"  Round {round_num}: sent={len(bot_ids)}, replied={responded}/{len(bot_ids)} "
          f"({time.time() - round_start:.1f}s)")


# ---------------------------------------------------------------------------
# Active mode — bots initiate conversations
# ---------------------------------------------------------------------------
async def active_bot_loop(bot_info: dict, bot_index: int, target_user_id: int,
                          interval: float, active_events: dict):
    """Connect as the bot, periodically send unique messages to the target user."""
    try:
        async with aiohttp.ClientSession() as session:
            token = await login(session, bot_info["username"], bot_info["password"])
            if not token:
                async with stats_lock:
                    stats.connect_failures += 1
                print(f"  [ACTIVE] {bot_info['nickname']}: login failed")
                return

        client = StompClient(token, bot_info["nickname"])
        await client.connect()
        print(f"  [ACTIVE] {bot_info['nickname']} connected")

        msg_from_target = {}
        async def on_user_msg(cmd, headers, body):
            if cmd == "MESSAGE":
                try:
                    data = json.loads(body)
                    if data.get("type") == "CHAT" and data.get("senderId") == target_user_id:
                        msg_from_target["last"] = time.time()
                        async with stats_lock:
                            stats.received += 1
                except json.JSONDecodeError:
                    pass

        await client.subscribe("/user/queue/private/chat", on_user_msg)

        # Use a personal opener rotation per bot
        opener_index = bot_index * 3  # stagger starting point
        while client.connected:
            await asyncio.sleep(interval + random.uniform(-2, 2))
            if not client.connected:
                break

            opener = ACTIVE_OPENERS[opener_index % len(ACTIVE_OPENERS)]
            opener_index += 1
            chat_msg = {
                "content": opener,
                "messageType": 0,
                "targetId": target_user_id,
                "contentType": 0,
                "clientMessageId": f"active_{uuid.uuid4().hex[:16]}",
            }
            try:
                start = time.time()
                await client.send("/app/chat.send", chat_msg)
                async with stats_lock:
                    stats.active_sent += 1
                    stats.sent += 1
                    stats.unique_messages.add(opener)
                latency = (time.time() - start) * 1000
                async with stats_lock:
                    stats.latencies.append(latency)
                active_events[bot_info["nickname"]] = active_events.get(bot_info["nickname"], 0) + 1
            except Exception:
                async with stats_lock:
                    stats.errors += 1
                break

        await client.disconnect()
    except Exception as e:
        async with stats_lock:
            stats.errors += 1
            stats.connect_failures += 1
        print(f"  [ACTIVE] {bot_info['nickname']}: error - {e}")


async def run_active_mode(bots: list[dict], target_user_id: int, interval: float,
                          duration: float):
    print(f"\n{'='*60}")
    print(f"  ACTIVE MODE: {len(bots)} bots initiating conversations")
    print(f"  Interval: {interval}s, Duration: {duration}s")
    print(f"{'='*60}")

    active_events = {}
    tasks = [asyncio.create_task(active_bot_loop(b, i, target_user_id, interval, active_events))
             for i, b in enumerate(bots)]

    try:
        await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), timeout=duration)
    except asyncio.TimeoutError:
        pass

    for t in tasks:
        if not t.done():
            t.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)

    active_total = sum(active_events.values())
    print(f"\n  Active messages sent: {active_total}")
    for name, count in sorted(active_events.items()):
        print(f"    {name}: {count} messages")


# ---------------------------------------------------------------------------
# Passive mode runner
# ---------------------------------------------------------------------------
async def run_passive_mode(token: str, bots: list[dict], rounds: int):
    print(f"\n{'='*60}")
    print(f"  PASSIVE MODE: user -> {len(bots)} bots x {rounds} rounds")
    print(f"  Message pool: 200+ unique questions, personality-matched")
    print(f"{'='*60}")

    user_client = StompClient(token, "main_user")
    await user_client.connect()
    print("  Main user connected via WebSocket")

    bot_ids = [b["userId"] for b in bots]
    selector = MessageSelector()

    async with aiohttp.ClientSession() as session:
        for r in range(1, rounds + 1):
            await passive_chat_round(user_client, bot_ids, r, selector)
            if r < rounds:
                await asyncio.sleep(2)

    await user_client.disconnect()

    # Cache effectiveness summary
    print(f"\n  [CACHE] Unique messages sent: {selector.unique_count}/{stats.sent}")
    cache_reuse_rate = (1 - selector.unique_count / max(stats.sent, 1)) * 100
    print(f"  [CACHE] Estimated cache miss rate: ~{100 - cache_reuse_rate:.0f}% "
          f"(unique content ensures minimal cache hits)")


# ---------------------------------------------------------------------------
# report
# ---------------------------------------------------------------------------
def print_report(start_time: float):
    elapsed = time.time() - start_time
    print(f"\n{'='*60}")
    print(f"  STRESS TEST RESULTS (v3 — diverse messages)")
    print(f"{'='*60}")
    print(f"  Duration:            {elapsed:.1f}s")
    print(f"  Messages sent:       {stats.sent}")
    print(f"  Active (bot→user):   {stats.active_sent}")
    print(f"  Messages received:   {stats.received}")
    print(f"  Bot replies:         {stats.bot_replies}")
    print(f"  Errors:              {stats.errors}")
    print(f"  Connect failures:    {stats.connect_failures}")
    print(f"  Unique messages:     {len(stats.unique_messages)}")
    if stats.sent > 0:
        uniqueness = len(stats.unique_messages) / stats.sent * 100
        print(f"  Uniqueness rate:     {uniqueness:.1f}% (↑ = fewer cache hits)")

    if stats.latencies:
        lats = sorted(stats.latencies)
        print(f"  Latency P50:         {lats[len(lats)//2]:.1f}ms")
        print(f"  Latency P95:         {lats[int(len(lats)*0.95)]:.1f}ms")
        print(f"  Latency P99:         {lats[int(len(lats)*0.99)]:.1f}ms")
        print(f"  Latency Max:         {max(lats):.1f}ms")

    total = max(stats.sent, 1)
    error_rate = (stats.errors / total) * 100
    reply_rate = (stats.bot_replies / max(stats.sent - stats.active_sent, 1)) * 100
    print(f"  Error rate:          {error_rate:.2f}%")
    print(f"  Bot reply rate:      {reply_rate:.1f}%")
    print()

    if error_rate < 5.0 and stats.connect_failures == 0:
        print("  [PASS] All bots chatting stably with diverse unique messages!")
    elif error_rate < 20.0:
        print("  [WARN] Some errors but system is functional")
    else:
        print("  [FAIL] High error rate — check server and API keys")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
async def main():
    parser = argparse.ArgumentParser(description="Chatroom 20-Bot Concurrent Chat Stress Test v3")
    parser.add_argument("--mode", choices=["passive", "active", "both"],
                        default="both", help="Chat mode (default: both)")
    parser.add_argument("--bots", type=int, default=20,
                        help="Number of bots to test (default: 20)")
    parser.add_argument("--rounds", type=int, default=3,
                        help="Rounds of messages in passive mode (default: 3)")
    parser.add_argument("--interval", type=float, default=15,
                        help="Seconds between active bot messages (default: 15)")
    parser.add_argument("--duration", type=float, default=60,
                        help="Duration in seconds for active mode (default: 60)")
    parser.add_argument("--clean", action="store_true", default=True,
                        help="Clean up old test bots before starting")
    parser.add_argument("--no-clean", dest="clean", action="store_false",
                        help="Skip cleaning old test bots")
    parser.add_argument("--user", type=str, default=None,
                        help="Existing username (skip registration)")
    args = parser.parse_args()

    print("=" * 60)
    print("  Chatroom 20-Bot Concurrent Chat Stress Test v3")
    print("  Message pool: 200+ unique, personality-matched questions")
    print("=" * 60)
    print(f"  Mode: {args.mode}, Bots: {args.bots}, Rounds: {args.rounds}")
    print(f"  Active interval: {args.interval}s, Duration: {args.duration}s")
    print()

    test_start = time.time()

    async with aiohttp.ClientSession() as session:
        # ---- Step 1: Account ----
        print("--- Step 1: Account ---")
        username = args.user or f"stress_test_{uuid.uuid4().hex[:6]}"
        token = await login(session, username, TEST_PASSWORD)
        actual_username = username
        if not token:
            print(f"  Registering new user: {username}")
            token, actual_username = await register_user(session, username, TEST_PASSWORD, f"StressTester_{username[:6]}")
            if not token:
                print("[FAIL] Cannot register or login. Is the server running?")
                return
        print(f"  Login account: {actual_username} / {TEST_PASSWORD}")
        print(f"  Token obtained: {token[:30]}...")

        # ---- Step 2: Clean old bots ----
        if args.clean:
            print("\n--- Step 2: Clean Old Bots ---")
            await cleanup_old_bots(session, token)

        # ---- Step 3: Register bots ----
        print(f"\n--- Step 3: Register {args.bots} Bots ---")
        bots = await register_bots(session, token, args.bots)
        if not bots:
            print("[FAIL] No bots registered. Check API key and server.")
            return

        # ---- Step 4: Add friends ----
        print(f"\n--- Step 4: Add Bots as Friends ---")
        bot_ids = [b["userId"] for b in bots]
        await add_friends(session, token, bot_ids)

        me_data = await api_get(session, "/auth/me", token)
        my_user_id = me_data.get("data", {}).get("id")

        # ---- Step 5: Run Tests ----
        if args.mode in ("passive", "both"):
            await run_passive_mode(token, bots, args.rounds)

        if args.mode in ("active", "both"):
            if my_user_id:
                if args.mode == "both":
                    await asyncio.sleep(3)
                await run_active_mode(bots, my_user_id, args.interval, args.duration)
            else:
                print("\n  [SKIP] Active mode requires user ID — check /auth/me endpoint")

    # ---- Report ----
    print_report(test_start)


if __name__ == "__main__":
    asyncio.run(main())
