"""导入用户图鉴；只匹配已存在的翻译键，歧义不猜。运行参数：txt 游戏实例 输出目录。"""
from pathlib import Path
from zipfile import ZipFile
import json,re,sys,collections

def generate(table,game,out):
    game=Path(game);out=Path(out);translations=collections.defaultdict(lambda:collections.defaultdict(set));english={}
    sources=list((game/'mods').glob('*.jar'))+list((game/'resourcepacks').glob('*.zip'))+list(game.glob('*.jar'))
    vanilla_keys=set()
    # 原版英文资源给出当前版本的正式键，排除整合包残留的旧OTG同名译名。
    for source in game.glob('*.jar'):
        try:
            with ZipFile(source) as z:
                vanilla_keys.update(json.loads(z.read('assets/minecraft/lang/en_us.json')))
        except (OSError,ValueError,KeyError):pass
    # Minecraft语言资源由资产索引定位，不依赖作者机器的具体hash。
    for root in (game.parent.parent,game.parent,game):
        assets=root/'assets'
        for index in (assets/'indexes').glob('*.json'):
            try:
                for name,obj in json.loads(index.read_text('utf-8'))['objects'].items():
                    if name.lower()=='minecraft/lang/zh_cn.json':
                        p=assets/'objects'/obj['hash'][:2]/obj['hash']
                        if p.exists():
                            for k,v in json.loads(p.read_text('utf-8')).items():translations[k][v].add('minecraft-assets')
            except (ValueError,OSError,KeyError):pass
        sources+=list((root/'versions'/'1.20.1').glob('*.jar'))
    for p in sources:
        try:
            with ZipFile(p) as z:
                for name in z.namelist():
                    if not re.match(r'assets/[^/]+/lang/(zh_cn|en_us)\.json$',name):continue
                    try:d=json.loads(z.read(name).decode('utf-8-sig'))
                    except (ValueError,UnicodeError):continue
                    for k,v in d.items():
                        if not isinstance(v,str):continue
                        if name.endswith('en_us.json'):english[k]=v
                        else:translations[k][v].add(p.name)
        except (OSError,ValueError):pass
    group_ns={'Minecraft':'minecraft','天境':'aether','诡厄巫法':'goety','Alex的生物':'alexsmobs','暮色森林':'twilightforest','灾变':'cataclysm','Alex的洞穴':'alexscaves','逝去的下界':'bygonenether','eombs':'eeeabsmobs','MMobs':'mowziesmobs','血肉重铸':'born_in_chaos_v1','冰火传说':'iceandfire','幽邃黑暗':'deeperdarker','The Graveyard':'graveyard','Illage and Spillage':'illageandspillage',"Iron's Spells 'n Spellbooks":'irons_spellbooks','诡厄乐事':'goetydelight','诡厄巫法：启示录':'goety_revelation','诡异巫法：启示录':'goety_revelation','It Takes a Pillage':'takesapillage','The Fool':'ageofmythology'}
    entities=collections.defaultdict(set);biomes=collections.defaultdict(set)
    for key,values in translations.items():
        if key.startswith('entity.') and len(key.split('.'))>=3:
            _,ns,path=key.split('.',2)
            for value in values:entities[(ns,value.strip())].add(ns+':'+path)
        if key.startswith('biome.') and len(key.split('.'))>=3:
            _,ns,path=key.split('.',2)
            for value in values:
                if value and len(value)>1:biomes[value].add(ns+':'+path)
    entries=[]
    for line in Path(table).read_text('utf-8-sig').splitlines():
        m=re.match(r'^【([^】]+)】(.*)$',line.strip())
        if m:entries.append({'group':m[1],'name':m[2].strip(),'note':[]})
        elif line.strip() and entries:entries[-1]['note'].append(line.strip())
    result={};pending=[]
    for e in entries:
        ns=group_ns.get(e['group']);name=e['name'];note=' '.join(e['note']);candidates=entities.get((ns,name),set())
        if not candidates:
            # 未配置模组别名时只接受全库唯一译名；名称带数字/变体不自动合并。
            candidates=set().union(*(ids for (owner,label),ids in entities.items() if label==name and (ns is None or ns==owner)))
        if len(candidates)!=1:
            pending.append({**e,'reason':'没有唯一注册翻译键','candidates':sorted(candidates)});continue
        eid=next(iter(candidates));ns,path=eid.split(':',1)
        if eid in ('minecraft:player','minecraft:armor_stand'):pending.append({**e,'reason':'非生物目标'});continue
        dims=[]
        for label,dim in [('主世界','minecraft:overworld'),('下界','minecraft:the_nether'),('末地','minecraft:the_end'),('天境','aether:the_aether'),('暮色森林','twilightforest:twilight_forest')]:
            if label in note:dims.append(dim)
        bio=[]
        remaining=note
        # 长名称优先，避免把“桦木森林”再识别成“森林”；仅接受唯一译名。
        for label in sorted(biomes,key=len,reverse=True):
            if label not in remaining or label in ('主世界','下界','末地','天境','暮色森林'):continue
            options=biomes[label]
            canonical={b for b in options if 'biome.'+b.replace(':','.',1) in vanilla_keys}
            if canonical:options=canonical
            if len(options)==1:
                bio+=list(options);remaining=remaining.replace(label,' ')
        if eid not in result:result[eid]={'entity':eid,'name':name,'english':english.get('entity.'+ns+'.'+path,path),'aliases':[name],'dimensions':dims,'biomes':sorted(set(bio)),'note':note}
        else:result[eid]['aliases'].append(name)
    out.mkdir(parents=True,exist_ok=True)
    (out/'user-book.json').write_text(json.dumps(list(result.values()),ensure_ascii=False,indent=2)+'\n','utf-8')
    report={'source_entries':len(entries),'matched_translation_keys':len(result),'unresolved':pending,'warning':'翻译键仅为导入依据；运行时还会核对实体注册ID和维度，缺少维度者不启用。'}
    return report

if __name__=='__main__':
    report=generate(*sys.argv[1:4]);print(json.dumps(report,ensure_ascii=False,indent=2))
