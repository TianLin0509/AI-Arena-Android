package com.tianlin.aiarena

/** Read-only readiness probes deliberately fail closed when a vendor changes its upload UI. */
internal object ArenaAttachmentScript {
    // 2026-09-15: the live composer wraps the area in one more div; keep the older direct path too.
    private const val doubaoAreaSelector = "[data-testid=attachment_area],.guidance-input-surface > .relative > .container-tJHWhP,.guidance-input-surface .container-tJHWhP"
    private const val qwenCloseSelector = "[data-chat-input-shell] [data-chat-input-top-content] [data-icon-type=qwpcicon-close2]"
    private const val yuanbaoCloseSelector = "[data-new-input-card] [data-input-resource-area] [aria-label=\"删除文件\"]"

    /** Draft cards in each composer. Qwen and Yuanbao are counted by their per-card delete controls. */
    private fun draftCardSelector(service: ArenaService): String = when (service) {
        ArenaService.DEEPSEEK -> "._77cefa5 ._25c7358,._77cefa5 .d5fa3d1b"
        ArenaService.KIMI -> "[data-testid=input-attachment-list] .file-card-container,[data-testid=input-attachment-list] .image-thumbnail,.chat-editor-attachment-area .file-card-container,.chat-editor-attachment-area .image-thumbnail"
        ArenaService.DOUBAO -> "[data-testid=attachment_area] [data-testid^=attachment_],.guidance-input-surface .container-tJHWhP [data-kind][role=button]"
        ArenaService.QWEN -> qwenCloseSelector
        ArenaService.YUANBAO -> yuanbaoCloseSelector
        // 智谱与境外成员没有草稿附件区，故意匹配不到任何节点。
        else -> "[data-arena-no-attachment-support]"
    }

    /**
     * Only delete controls observed on the real site (2026-09-15, isolated account clone) are listed.
     * Unknown structures return null, so the existing "remove it in the original page" stop remains.
     */
    private fun draftDeleteControl(service: ArenaService): String = when (service) {
        ArenaService.QWEN, ArenaService.YUANBAO -> "card=>card"
        ArenaService.DEEPSEEK -> "card=>{const controls=Array.from(card.querySelectorAll(':scope > [tabindex=\"0\"]')).filter(e=>e.querySelector('.ds-icon'));return controls.length===1?controls[0]:null;}"
        ArenaService.DOUBAO -> "card=>{const controls=Array.from(card.querySelectorAll(':scope > svg[aria-label=delete]'));return controls.length===1?controls[0]:null;}"
        ArenaService.KIMI -> "card=>{const controls=Array.from(card.querySelectorAll(card.matches('.image-thumbnail')?':scope > .image-delete-container':':scope > .file-card-delete'));return controls.length===1?controls[0]:null;}"
        else -> "card=>null"
    }

    /**
     * A failed earlier round, or a site restoring its own draft, leaves cards in the composer and would
     * stop every later round. Remove them only through each card's own delete control, and only when every
     * card has exactly one known control; otherwise report them and let the caller stop as before.
     */
    fun leftovers(service: ArenaService, remove: Boolean): String = """
        (() => {
          const shown=e=>{const r=e.getBoundingClientRect();return r.width>2&&r.height>2&&getComputedStyle(e).visibility!=='hidden'};
          const cards=Array.from(document.querySelectorAll(${ArenaJs.quote(draftCardSelector(service))})).filter(shown);
          const controlOf=${draftDeleteControl(service)};
          const controls=cards.map(controlOf).filter(control=>control&&control.isConnected);
          let clicked=0;
          if($remove&&cards.length&&controls.length===cards.length){
            for(const control of controls){control.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));clicked++;}
          }
          return JSON.stringify({count:cards.length,removable:controls.length,clicked});
        })();
    """.trimIndent()

    fun prepare(requestId: String, files: List<ArenaAttachment>, service: ArenaService): String {
        val expected = files.joinToString(",", "[", "]") { "{name:${ArenaJs.quote(it.name)},size:${it.sizeBytes},mime:${ArenaJs.quote(it.mimeType)}}" }
        return """
            (() => {
              const previous = window.__arenaAttachment;
              $reactHelpers
              ${if (service == ArenaService.DEEPSEEK) deepSeekIdentityHelpers else ""}
              if (previous && previous.listener) document.removeEventListener('change', previous.listener, true);
              if (previous && previous.listener) for(const input of previous.selectionInputs||[previous.selectionInput]) if(input)input.removeEventListener('change',previous.listener,true);
              if (previous && previous.pendingControl?.clickTarget) previous.pendingControl.clickTarget.removeEventListener('click', previous.pendingControl.clickListener, true);
              if (previous && previous.observer) previous.observer.disconnect();
              const shown=e=>{const r=e.getBoundingClientRect();return r.width>2&&r.height>2&&getComputedStyle(e).visibility!=='hidden'};
              const existing=Array.from(document.querySelectorAll(${ArenaJs.quote(draftCardSelector(service))})).filter(shown);
              document.querySelectorAll(${ArenaJs.quote(doubaoAreaSelector)}).forEach(area=>{const props=reactProps(area,'attachmentStates');if(props&&props.attachmentStates.length)existing.push(area);});
              if(existing.length) return JSON.stringify({error:'原网页还有未发送的附件，自动清理没有完成，请先到原网页移除后重试，避免重复上传'});
              const previousLocalIds=new Set(Array.from(document.querySelectorAll('._77cefa5 ._25c7358,._77cefa5 .d5fa3d1b')).map(card=>reactProps(card,'file')?.file?.localId).filter(id=>typeof id==='string'&&id));
              const state = {id:${ArenaJs.quote(requestId)},expected:$expected,chosen:false,selectionConfirmed:false,selectionInput:null,selectionInputs:new Set(),clicked:new WeakSet(),menuAttempts:new Map(),kimiMenuAttempt:null,kimiFileAttempt:null,fileControlAttempt:null,deepSeekControlAttempt:null,controlSequence:0,pendingControl:null,baseline:new Set(document.querySelectorAll('*')),imageWasLoading:new WeakSet(),deepSeekLocalIds:[],deepSeekCandidates:{},previousLocalIds};
              const rememberImages=()=>{
                resetReactRead();
                document.querySelectorAll('[data-testid="input-attachment-list"] .image-thumbnail,.chat-editor-attachment-area .image-thumbnail').forEach(card=>{
                  if(!state.baseline.has(card)&&card.classList.contains('loading'))state.imageWasLoading.add(card);
                });
                ${if (service == ArenaService.DEEPSEEK) "rememberDeepSeekFiles(state);" else ""}
              };
              state.observer=new MutationObserver(rememberImages);
              state.observer.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class']});
              state.listener = e => {
                if (window.__arenaAttachment !== state || !e.target || e.target.type !== 'file') return;
                ${if (service == ArenaService.KIMI) "if(!state.selectionInputs.has(e.target))return;" else ""}
                const actual = Array.from(e.target.files || []);
                state.chosen = actual.length === state.expected.length && state.expected.every(f => actual.some(a => a.name === f.name && a.size === f.size));
                state.selectionConfirmed = state.chosen;
                if (!state.chosen) state.error = '网页收到的附件与所选附件不一致';
              };
              // Qwen and Yuanbao create their file inputs under body on demand; change still bubbles here.
              document.addEventListener('change', state.listener, true);
              window.__arenaAttachment = state;
              return true;
            })();
        """.trimIndent()
    }

    private val helpers = """
        const visible = e => { if (!e) return false; const r=e.getBoundingClientRect(),s=getComputedStyle(e); return r.width>2&&r.height>2&&s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0'; };
        $reactHelpers
        const state = window.__arenaAttachment;
    """.trimIndent()

    internal val reactHelpers: String get() = """
        // Shared bailout children may retain return pointers to the other parent branch.
        // Index actual current child/sibling edges once per synchronous read, including
        // the ancestor path; choosing only a leaf then following return is insufficient.
        let reactIndexes=new Map();
        const resetReactRead=()=>{reactIndexes=new Map();};
        const currentReactPath=element=>{
          if(!element||!element.isConnected)return null;
          const raw=element[Object.keys(element).find(k=>k.startsWith('__reactFiber${'$'}'))];
          let currentRoot=null;
          for(const candidate of [raw,raw&&raw.alternate]){
            let root=candidate,depth=0;const seen=new Set();
            while(root&&root.return){if(seen.has(root)||depth++>=512){root=null;break;}seen.add(root);root=root.return;}
            if(root&&root.tag===3&&root.stateNode&&root.stateNode.current&&root.stateNode.current.tag===3){currentRoot=root.stateNode.current;break;}
          }
          if(!currentRoot)return null;
          let index=reactIndexes.get(currentRoot);
          if(!index){
            const parents=new Map(),hosts=new Set(),stack=[[currentRoot,null,0]];let valid=true;
            while(stack.length){
              const [fiber,parent,depth]=stack.pop();
              if(!fiber||parents.has(fiber)||parents.size>=25000||depth>512){valid=false;break;}
              parents.set(fiber,parent);
              if(fiber.stateNode instanceof Node){if(hosts.has(fiber.stateNode)){valid=false;break;}hosts.add(fiber.stateNode);}
              if(fiber.sibling)stack.push([fiber.sibling,parent,depth]);
              if(fiber.child)stack.push([fiber.child,fiber,depth+1]);
            }
            index={parents,valid};reactIndexes.set(currentRoot,index);
          }
          if(!index.valid)return null;
          const matches=[raw,raw&&raw.alternate].filter(f=>f&&index.parents.has(f)&&f.stateNode===element);
          if(matches.length!==1)return null;
          const path=[];let fiber=matches[0];
          while(fiber){path.push(fiber);fiber=index.parents.get(fiber);}
          return path;
        };
        const reactProps=(element,key)=>{const path=currentReactPath(element);if(!path)return null;for(const fiber of path.slice(0,15)){if(fiber.memoizedProps&&fiber.memoizedProps[key])return fiber.memoizedProps;}return null;};
    """.trimIndent()

    // Prefer binding original metadata before DeepSeek's WebP conversion; readiness also
    // handles batched conversion after exact chooser confirmation and stable unique matching.
    private val deepSeekIdentityHelpers = """
        const rememberDeepSeekFiles=state=>{
          if(!state.chosen)return;
          const reserved=new Set(state.deepSeekLocalIds.filter(Boolean));
          document.querySelectorAll('._77cefa5 ._25c7358,._77cefa5 .d5fa3d1b').forEach(card=>{
            if(state.baseline.has(card))return;
            const props=reactProps(card,'file'),file=props&&props.file;
            if(!file||typeof file.localId!=='string'||!file.localId||reserved.has(file.localId)||state.previousLocalIds.has(file.localId))return;
            const index=state.expected.findIndex((expected,i)=>!state.deepSeekLocalIds[i]&&expected.mime.startsWith('image/')&&file.fileName===expected.name&&Number(file.fileSize)===expected.size);
            if(index>=0){state.deepSeekLocalIds[index]=file.localId;reserved.add(file.localId);}
          });
        };
    """.trimIndent()

    fun nextControl(requestId: String, service: ArenaService): String = """
        (() => {
          $helpers
          if (!state || state.id !== ${ArenaJs.quote(requestId)}) return JSON.stringify({error:'附件请求已取消'});
          const candidates = [];
          let menuItemEl=null,menuTriggerEl=null,menuTargetEl=null,kimiFileLabel=null,kimiFileInput=null,kimiFileTrigger=null,deepSeekInput=null,deepSeekTarget=null;
          const openMenus=trigger=>{
            ${if (service == ArenaService.YUANBAO) {
                "const holder=trigger.closest('[data-new-input-control=add-tools]')||trigger.parentElement;return Array.from(holder.querySelectorAll('[role=menu]')).filter(visible);"
            } else {
                "const controls=(trigger.getAttribute('aria-controls')||'').split(/\\s+/);return Array.from(document.querySelectorAll('[role=menu]')).filter(menu=>visible(menu)&&(controls.includes(menu.id)||(menu.getAttribute('aria-labelledby')||'').split(/\\s+/).includes(trigger.id)));"
            }}
          };
          const menuKey=trigger=>trigger.id||trigger.getAttribute('data-new-input-control')||'menu';
          ${when(service) {
              ArenaService.DEEPSEEK -> """
                if(state.deepSeekControlAttempt?.count>=3&&Date.now()-state.deepSeekControlAttempt.at>=2000)return JSON.stringify({error:'DeepSeek 本地附件入口连续 3 次未响应，未发送问题；请打开原网页检查后重试'});
                const scopes=Array.from(new Set(Array.from(document.querySelectorAll('textarea')).filter(visible).map(input=>input.closest('._77cefa5')).filter(Boolean)));
                if(scopes.length>1)return JSON.stringify({error:'无法确认 DeepSeek 唯一附件输入区，未发送问题'});
                const scope=scopes[0];if(!scope)return JSON.stringify({waiting:true});
                const inputs=Array.from(scope.querySelectorAll('input[type=file]'));
                if(inputs.length>1)return JSON.stringify({error:'无法确认 DeepSeek 唯一附件入口，未发送问题'});
                deepSeekInput=inputs[0];if(!deepSeekInput)return JSON.stringify({waiting:true});
                const previous=state.deepSeekControlAttempt;
                if(previous&&(previous.scope!==scope||previous.input!==deepSeekInput))return JSON.stringify({error:'DeepSeek 附件入口已变化，未发送问题；请打开原网页检查后重试'});
                if(state.chosen||deepSeekInput.disabled||!deepSeekInput.files||deepSeekInput.files.length)return JSON.stringify({waiting:true});
              """.trimIndent()
              ArenaService.DOUBAO -> """
                const surfaces=Array.from(document.querySelectorAll('.guidance-input-surface')).filter(e=>visible(e)&&e.querySelector('textarea,[contenteditable=true]')&&e.querySelector('input[type=file]'));
                const scope=surfaces.length===1?surfaces[0]:document;
                if(surfaces.length===1){
                  const triggers=Array.from(scope.querySelectorAll('.guidance-input-actions button[data-slot=dropdown-menu-trigger][aria-haspopup=menu]')).filter(e=>visible(e)&&e.id&&e.querySelector('button[data-dbx-name=button][aria-haspopup=menu]'));
                  if(triggers.length===1){
                    const trigger=triggers[0];
                    const inner=Array.from(trigger.querySelectorAll('button[data-dbx-name=button][aria-haspopup=menu]'));
                    if(inner.length===1){
                      menuTriggerEl=trigger;menuTargetEl=inner[0];
                      const menus=Array.from(document.querySelectorAll('[role=menu][data-slot=dropdown-menu-content]')).filter(e=>visible(e)&&(e.getAttribute('aria-labelledby')||'').split(/\s+/).includes(trigger.id));
                      if(menus.length===1){
                        const local=Array.from(menus[0].querySelectorAll('[role=menuitem][data-slot=dropdown-menu-item]')).filter(e=>e.closest('[role=menu]')===menus[0]&&(e.textContent||'').replace(/\s+/g,'')==='上传文件或图片');
                        if(local.length===1)candidates.push(local[0]);
                      }else if(menus.length===0)candidates.push(menuTargetEl);
                    }
                  }
                }else if(surfaces.length===0){
                  document.querySelectorAll('[data-testid=upload_file_button]').forEach(e=>candidates.push(e));
                }
              """.trimIndent()
              ArenaService.KIMI -> """
                if(state.error)return JSON.stringify({error:state.error});
                if(state.chosen||Array.from(state.selectionInputs).some(input=>input.files?.length))return JSON.stringify({waiting:true});
                const fileAttempt=state.kimiFileAttempt;
                if(fileAttempt?.count>=3&&Date.now()-fileAttempt.at>=2000)return JSON.stringify({error:'Kimi 本地附件入口连续 3 次未响应，未发送问题；请打开原网页检查后重试'});
                const triggers=Array.from(document.querySelectorAll('.toolkit-trigger-btn')).filter(visible);
                if(triggers.length>1)return JSON.stringify({error:'无法确认 Kimi 唯一附件菜单，未发送问题'});
                const trigger=triggers[0];const scope=document;
                if(trigger&&trigger.id&&trigger.getAttribute('aria-haspopup')==='menu'){
                  const controls=(trigger.getAttribute('aria-controls')||'').split(/\s+/);
                  const menus=Array.from(document.querySelectorAll('[role=menu]')).filter(menu=>visible(menu)&&(controls.includes(menu.id)||(menu.getAttribute('aria-labelledby')||'').split(/\s+/).includes(trigger.id)));
                  if(trigger.getAttribute('aria-expanded')==='true'&&menus.length===1){
                    const labels=Array.from(menus[0].querySelectorAll('label.toolkit-item[role=menuitem]')).filter(label=>visible(label)&&label.closest('[role=menu]')===menus[0]&&label.querySelectorAll('input[type=file]').length===1);
                    if(labels.length===1){
                      const label=labels[0],input=label.querySelector('input[type=file]'),previous=state.fileControlAttempt;
                      const same=!previous||(previous.label===label&&previous.input===input&&previous.triggerId===trigger.id);
                      const oldStillUsable=previous&&previous.input.isConnected&&visible(previous.label)&&previous.label.closest('[role=menu]')===menus[0]&&previous.label.contains(previous.input);
                      if(same||(!oldStillUsable&&fileAttempt&&Date.now()-fileAttempt.at>=2000)){
                        if(!state.chosen&&input.files&&input.files.length===0&&!input.disabled){kimiFileLabel=label;kimiFileInput=input;kimiFileTrigger=trigger;candidates.push(label);}
                      }
                    }
                  }else if(trigger.getAttribute('aria-expanded')==='false'&&menus.length===0&&(!fileAttempt||Date.now()-fileAttempt.at>=2000))candidates.push(trigger);
                }
              """.trimIndent()
              ArenaService.QWEN -> """
                if(state.error)return JSON.stringify({error:state.error});
                if(state.chosen)return JSON.stringify({waiting:true});
                const shells=Array.from(document.querySelectorAll('[data-chat-input-shell]')).filter(e=>visible(e)&&e.querySelector('[contenteditable=true],textarea'));
                if(shells.length>1)return JSON.stringify({error:'无法确认千问唯一输入区，未发送问题'});
                const scope=shells[0];if(!scope)return JSON.stringify({waiting:true});
                const triggers=Array.from(scope.querySelectorAll('button[aria-haspopup=menu][aria-label="添加附件"]')).filter(visible);
                if(triggers.length>1)return JSON.stringify({error:'无法确认千问唯一附件菜单，未发送问题'});
                const trigger=triggers[0];if(!trigger||!trigger.id)return JSON.stringify({waiting:true});
                menuTriggerEl=trigger;menuTargetEl=trigger;
                // Qwen keeps images and documents behind separate items with different accept lists.
                const label=state.expected.every(f=>f.mime.startsWith('image/'))?'上传图片':'上传文档';
                const menus=openMenus(trigger);
                if(menus.length===1){
                  const items=Array.from(menus[0].querySelectorAll('[role=menuitem]')).filter(e=>visible(e)&&e.closest('[role=menu]')===menus[0]&&(e.textContent||'').replace(/\s+/g,'')===label);
                  if(items.length>1)return JSON.stringify({error:'千问上传菜单出现多个“'+label+'”，未发送问题'});
                  if(items.length===1){menuItemEl=items[0];candidates.push(items[0]);}
                }else if(menus.length===0)candidates.push(trigger);
              """.trimIndent()
              ArenaService.YUANBAO -> """
                if(state.error)return JSON.stringify({error:state.error});
                if(state.chosen)return JSON.stringify({waiting:true});
                const cards=Array.from(document.querySelectorAll('[data-new-input-card]')).filter(e=>visible(e)&&e.querySelector('[contenteditable=true]'));
                if(cards.length>1)return JSON.stringify({error:'无法确认元宝唯一输入区，未发送问题'});
                const scope=cards[0];if(!scope)return JSON.stringify({waiting:true});
                const triggers=Array.from(scope.querySelectorAll('button[data-new-input-control=add-tools-trigger]')).filter(visible);
                if(triggers.length>1)return JSON.stringify({error:'无法确认元宝唯一附件菜单，未发送问题'});
                const trigger=triggers[0];if(!trigger)return JSON.stringify({waiting:true});
                menuTriggerEl=trigger;menuTargetEl=trigger;
                // Menu labels follow the page language; React keys do not.
                const wantImage=state.expected.every(f=>f.mime.startsWith('image/'));
                const itemKey=wantImage?'upload_pic':'local_file',itemTexts=wantImage?['uploadimage','上传图片']:['localfiles','本地文件','上传文件'];
                const menus=openMenus(trigger);
                if(menus.length===1){
                  const all=Array.from(menus[0].querySelectorAll('[role=menuitem]')).filter(visible);
                  let items=all.filter(e=>(currentReactPath(e)||[]).slice(0,6).some(f=>f.key===itemKey));
                  if(!items.length)items=all.filter(e=>itemTexts.includes((e.textContent||'').replace(/\s+/g,'').toLowerCase()));
                  if(items.length>1)return JSON.stringify({error:'元宝上传菜单项不唯一，未发送问题'});
                  if(items.length===1){menuItemEl=items[0];candidates.push(items[0]);}
                }else if(menus.length===0)candidates.push(trigger);
              """.trimIndent()
              else -> "return JSON.stringify({error:'该成员暂不支持附件'});const scope=document;"
          }}
          scope.querySelectorAll('input[type=file]').forEach(input => {
            ${if(service == ArenaService.KIMI) "return; // Kimi local input is only eligible through the unique bound open menu above." else ""}
            ${if(service == ArenaService.QWEN || service == ArenaService.YUANBAO) "return; // Their inputs are created by the chosen menu item." else ""}
            ${if(service == ArenaService.DOUBAO) "if(!input.matches('[data-testid=upload-file-input]')&&!input.closest('[data-testid=upload_file_button]'))return;" else ""}
            if (visible(input)) candidates.push(input);
            if(input.id) document.querySelectorAll('label').forEach(label => { if(label.htmlFor===input.id${if(service == ArenaService.DEEPSEEK) "&&scope.contains(label)" else ""}) candidates.push(label); });
            const parent=input.parentElement;
            if(parent && parent.matches('label,button,[role=button]')) candidates.push(parent);
            // DeepSeek keeps a hidden input immediately after its visible upload control.
            if(input.previousElementSibling) {
              const sibling=input.previousElementSibling;
              if(sibling.matches('button,[role=button]')) candidates.push(sibling);
              else sibling.querySelectorAll('button,[role=button]').forEach(e=>candidates.push(e));
            }
          });
          const kimiTrigger=e=>${if (service == ArenaService.KIMI) "e.matches('.toolkit-trigger-btn')&&e.id&&e.getAttribute('aria-haspopup')==='menu'" else "false"};
          const menuTrigger=e=>kimiTrigger(e)?e:(e===menuTargetEl?menuTriggerEl:null);
          const closedMenu=e=>{
            const trigger=menuTrigger(e);
            if(!trigger||state.chosen||trigger.getAttribute('aria-expanded')!=='false')return false;
            return openMenus(trigger).length===0;
          };
          const eligible=candidates.filter(e=>visible(e)&&getComputedStyle(e).pointerEvents!=='none'&&!e.disabled&&e.getAttribute('aria-disabled')!=='true'&&!['','true'].includes(e.getAttribute('data-disabled'))&&!e.classList.contains('disabled'));
          ${if (service == ArenaService.DEEPSEEK) """
            const directTargets=Array.from(new Set(eligible));
            if(directTargets.length>1)return JSON.stringify({error:'无法确认 DeepSeek 唯一本地上传控件，未发送问题'});
            deepSeekTarget=directTargets[0]||null;
          """.trimIndent() else ""}
          const target=eligible.find(e=>{
            // 2026-09-15 real run: one native tap on Qwen's open menu item was not activated. Items keep a
            // request-wide budget like triggers: at most three taps, two seconds apart, while it stays unchosen.
            if(e===menuItemEl){const attempt=state.menuItemAttempt;return !attempt||(attempt.count<3&&Date.now()-attempt.at>=2000);}
            if(e===deepSeekTarget){const attempt=state.deepSeekControlAttempt;return !attempt||(attempt.count<3&&Date.now()-attempt.at>=2000);}
            if(e===kimiFileLabel){const attempt=state.kimiFileAttempt;return !attempt||(attempt.count<3&&Date.now()-attempt.at>=2000);}
            const trigger=menuTrigger(e),attempt=kimiTrigger(e)?state.kimiMenuAttempt:trigger&&state.menuAttempts.get(menuKey(trigger));
            if(!state.clicked.has(e)&&!attempt)return true;
            return attempt&&attempt.count<3&&Date.now()-attempt.at>=2000&&closedMenu(e);
          });
          if(!target){
            if(menuItemEl&&eligible.includes(menuItemEl)&&state.menuItemAttempt?.count>=3&&Date.now()-state.menuItemAttempt.at>=2000)return JSON.stringify({error:${ArenaJs.quote(service.displayName + " 上传菜单项连续 3 次未响应，未发送问题；请打开原网页检查后重试")}});
            if(kimiFileLabel&&eligible.includes(kimiFileLabel)&&state.fileControlAttempt?.count>=3&&Date.now()-state.fileControlAttempt.at>=2000)return JSON.stringify({error:'Kimi 本地附件入口连续 3 次未响应，未发送问题；请打开原网页检查后重试'});
            if(eligible.some(e=>{const trigger=menuTrigger(e),attempt=kimiTrigger(e)?state.kimiMenuAttempt:trigger&&state.menuAttempts.get(menuKey(trigger));return attempt&&attempt.count>=3&&Date.now()-attempt.at>=2000&&closedMenu(e);}))return JSON.stringify({error:${ArenaJs.quote(service.displayName + " 附件菜单连续 3 次未响应，未发送问题；请打开原网页检查后重试")}});
            return JSON.stringify({waiting:true});
          }
          target.scrollIntoView({block:'nearest',inline:'nearest'});
          const r=target.getBoundingClientRect(); const x=r.left+r.width/2,y=r.top+r.height/2;
          if(x<0||y<0||x>innerWidth||y>innerHeight) return JSON.stringify({waiting:true});
          const hit=document.elementFromPoint(x,y);
          if(!hit || !(target===hit || target.contains(hit) || hit.contains(target))) return JSON.stringify({waiting:true});
          const clickedTrigger=menuTrigger(target);
          if(state.pendingControl?.clickTarget)state.pendingControl.clickTarget.removeEventListener('click',state.pendingControl.clickListener,true);
          const hold={id:++state.controlSequence,target,wasClicked:state.clicked.has(target),menuId:clickedTrigger?menuKey(clickedTrigger):null,menuAttempt:clickedTrigger?state.menuAttempts.get(menuKey(clickedTrigger)):null,isFileTarget:target===kimiFileLabel,fileAttempt:state.fileControlAttempt,isMenuItem:target===menuItemEl,itemAttempt:state.menuItemAttempt,clickComplete:false,clickTarget:kimiFileInput||target};
          hold.clickListener=event=>{
            if(window.__arenaAttachment!==state||state.pendingControl!==hold)return;
            if(kimiFileInput&&event.target!==kimiFileInput)return;
            // A capture event precedes the browser's default activation. A later task acknowledges its completion.
            setTimeout(()=>{if(window.__arenaAttachment===state&&state.pendingControl===hold)hold.clickComplete=true;},0);
          };
          hold.clickTarget.addEventListener('click',hold.clickListener,true);
          state.pendingControl=hold;
          state.clicked.add(target);
          if(target===deepSeekTarget){
            // This request-wide budget survives button replacement and untouched geometry rollback.
            // A new file input or composer cannot inherit a previous input's upload authority.
            const previous=state.deepSeekControlAttempt;state.deepSeekControlAttempt={scope,input:deepSeekInput,count:(previous?.count||0)+1,at:Date.now()};
          }
          if(target===kimiFileLabel){
            // Kimi closes and unmounts the menu on input click, before chooser change can bubble to document.
            // Previously attempted inputs can receive a legitimate late chooser response after menu unmount.
            // Only this request's bounded, verified inputs retain authority; native broker still delivers once.
            if(!state.selectionInputs.has(kimiFileInput)){state.selectionInputs.add(kimiFileInput);kimiFileInput.addEventListener('change',state.listener,true);}
            state.selectionInput=kimiFileInput;
            const attempt={count:(state.kimiFileAttempt?.count||0)+1,at:Date.now()};state.kimiFileAttempt=attempt;
            state.fileControlAttempt={label:target,input:kimiFileInput,triggerId:kimiFileTrigger.id,...attempt};
          }
          if(target===menuItemEl)state.menuItemAttempt={count:(state.menuItemAttempt?.count||0)+1,at:Date.now()};
          if(kimiTrigger(target))state.kimiMenuAttempt={count:(state.kimiMenuAttempt?.count||0)+1,at:Date.now()};
          if(clickedTrigger){const key=menuKey(clickedTrigger),previous=state.menuAttempts.get(key);state.menuAttempts.set(key,{count:(previous?.count||0)+1,at:Date.now()});}
          return JSON.stringify({x,y,width:innerWidth,height:innerHeight,controlId:hold.id,retryableTap:!!clickedTrigger||target===kimiFileLabel||target===deepSeekTarget||target===menuItemEl});
        })();
    """.trimIndent()

    fun readiness(requestId: String, service: ArenaService): String = """
        (() => {
          $helpers
          if(!state || state.id!==${ArenaJs.quote(requestId)}) return JSON.stringify({error:'附件请求已取消'});
          if(state.error) return JSON.stringify({error:state.error});
          if(!state.chosen) return JSON.stringify({ready:false,detail:'等待网页接收附件'});
          ${vendorReadiness(service)}
          return JSON.stringify({ready:false,detail:'当前网页附件结构未识别，未发送问题'});
        })();
    """.trimIndent()

    /** Only called while native has not dispatched DOWN for this exact control snapshot. */
    fun releaseUnsentControl(requestId: String, controlId: Long): String = """
        (()=>{
          const state=window.__arenaAttachment,hold=state&&state.pendingControl;
          if(!state||state.id!==${ArenaJs.quote(requestId)}||!hold||hold.id!==$controlId||state.chosen)return false;
          if(!hold.wasClicked)state.clicked.delete(hold.target);
          if(hold.menuId){if(hold.menuAttempt)state.menuAttempts.set(hold.menuId,hold.menuAttempt);else state.menuAttempts.delete(hold.menuId);}
          if(hold.isMenuItem)state.menuItemAttempt=hold.itemAttempt;
          if(hold.isFileTarget){
            // A previous native chooser may arrive late. Keep this exact input bound until request cleanup.
            state.fileControlAttempt=hold.fileAttempt;
          }
          if(hold.clickTarget)hold.clickTarget.removeEventListener('click',hold.clickListener,true);
          state.pendingControl=null;
          return true;
        })();
    """.trimIndent()

    fun clickCompleted(requestId: String, controlId: Long): String = """
        (()=>{
          const state=window.__arenaAttachment,hold=state&&state.pendingControl;
          if(!state||state.id!==${ArenaJs.quote(requestId)}||!hold||hold.id!==$controlId||!hold.clickComplete)return false;
          hold.clickTarget.removeEventListener('click',hold.clickListener,true);
          return true;
        })();
    """.trimIndent()

    private fun vendorReadiness(service: ArenaService): String = when (service) {
        ArenaService.DEEPSEEK -> """
          $deepSeekIdentityHelpers
          rememberDeepSeekFiles(state);
          const input=Array.from(document.querySelectorAll('textarea')).find(visible);
          const composer=input&&input.closest('._77cefa5');
          const cards=composer?Array.from(composer.querySelectorAll('._25c7358,.d5fa3d1b')).filter(e=>visible(e)&&!state.baseline.has(e)):[];
          if(cards.length) {
            const allEntries=cards.map(card=>({card,props:reactProps(card,'file')})).filter(entry=>entry.props&&entry.props.file);
            if(allEntries.length!==cards.length)return JSON.stringify({ready:false,detail:'无法确认 DeepSeek 当前附件处理状态，暂未发送问题'});
            const localIds=allEntries.map(({props})=>props.file.localId).filter(id=>typeof id==='string'&&id);
            if(cards.length>state.expected.length||localIds.some(id=>state.previousLocalIds.has(id))||new Set(localIds).size!==localIds.length)return JSON.stringify({error:'DeepSeek 出现额外、重复或旧附件身份，未发送问题'});
            const canConvert=expected=>expected.mime.startsWith('image/')&&(/\.(jpe?g|png|heic|heif)${'$'}/i.test(expected.name)||['image/jpeg','image/png','image/heic','image/heif'].includes(expected.mime));
            const webpName=expected=>{const dot=expected.name.lastIndexOf('.');return (dot>0?expected.name.slice(0,dot):expected.name)+'.webp';};
            let complete=0;const used=new Set();
            for(const [index,expected] of state.expected.entries()) {
              const localId=state.deepSeekLocalIds[index],isImage=expected.mime.startsWith('image/');
              const convertible=canConvert(expected),convertedName=webpName(expected);
              const entries=allEntries.filter(({card})=>!used.has(card));
              let deferredIdentity=false;
              let entry=entries.find(({props})=>{
                const file=props.file,exact=file.fileName===expected.name&&Number(file.fileSize)===expected.size;
                if(localId&&file.localId!==localId)return false;
                return exact||(convertible&&localId&&file.isImage===true&&file.fileName===convertedName&&Number(file.fileSize)>0);
              });
              if(!entry&&convertible&&!localId){
                const converted=entries.filter(({props})=>props.file.fileName===convertedName&&props.file.isImage===true);
                if(converted.length){
                  const originals=state.expected.filter(other=>other.name===convertedName||(canConvert(other)&&webpName(other)===convertedName));
                  if(!state.selectionConfirmed||originals.length!==1||converted.length!==1)return JSON.stringify({error:expected.name+'：DeepSeek 图片转换身份不唯一或选图未确认，未发送问题'});
                  entry=converted[0];deferredIdentity=true;
                }
              }
              if(!entry){
                if(allEntries.length===state.expected.length&&allEntries.every(({props})=>props.file.status==='SUCCESS'))return JSON.stringify({error:expected.name+'：DeepSeek 附件名称或身份不匹配，未发送问题'});
                continue;
              }
              const {card,props}=entry,file=props.file;used.add(card);
              if(card.querySelector('._6e3a316,.a1310f97,._8753412')) return JSON.stringify({error:expected.name+'：DeepSeek 附件解析失败'});
              if(['FAILED','ERROR','REJECTED','CONTENT_FILTER','CONTENT_TOO_LONG','CANCELLED','CONTENT_EMPTY','_CUSTOM_SYSTEM_ERROR_FAIL'].includes(file.status)||file.auditResult==='reject'||props.fileUploadInfo?.failed||props.fileErrorState?.hasError)return JSON.stringify({error:expected.name+'：DeepSeek 附件上传、解析或审核失败'});
              if(props.fileUploadInfo?.isUploading||props.isForking)continue;
              if(file.status!=='SUCCESS'||!file.id||file.id===file.localId||(isImage&&(file.isImage!==true||![null,'pass'].includes(file.auditResult??null))))continue;
              if(deferredIdentity){
                if(allEntries.length!==state.expected.length||localIds.length!==state.expected.length||!Number.isSafeInteger(Number(file.fileSize))||Number(file.fileSize)<=0)continue;
                const signature=JSON.stringify([file.localId,file.id,file.fileName,Number(file.fileSize)]),previous=state.deepSeekCandidates[index];
                if(previous&&previous.signature!==signature)return JSON.stringify({error:expected.name+'：DeepSeek 图片确认期间身份发生变化，未发送问题'});
                if(!previous){state.deepSeekCandidates[index]={signature,at:Date.now()};continue;}
                if(Date.now()-previous.at<500)continue;
                state.deepSeekLocalIds[index]=file.localId;
              }
              complete++;
            }
            return JSON.stringify({ready:complete===state.expected.length&&cards.length===state.expected.length,detail:'DeepSeek 已确认 '+complete+'/'+state.expected.length+' 个附件解析完成'});
          }
        """.trimIndent()
        ArenaService.DOUBAO -> """
          const areas=Array.from(document.querySelectorAll(${ArenaJs.quote(doubaoAreaSelector)}));
          const area=areas.length===1?areas[0]:null;
          if(area) {
            const props=reactProps(area,'attachmentStates'),files=props&&props.attachmentStates;
            if(!Array.isArray(files))return JSON.stringify({ready:false,detail:'无法确认豆包附件处理状态'});
            if(files.length>state.expected.length)return JSON.stringify({error:'豆包出现额外附件或恢复的旧草稿，未发送问题；请打开原网页检查未发送的附件'});
            let complete=0;
            for(const expected of state.expected) {
              const file=files.find(f=>f.fileName===expected.name&&Number(f.size)===expected.size);
              if(!file)continue;
              if(file.status==='Retry'||file.parseState===2||file.reviewState===2)return JSON.stringify({error:expected.name+'：豆包附件上传、解析或审核失败'});
              if(file.status!=='Normal'||!file.fileKey||!file.localKey||![0,1].includes(file.reviewState??0))continue;
              if(expected.mime.startsWith('image/')) {
                // Normal + the matching remote key confirms upload; blobUrl remains the local preview.
                const remote=(file.imageList||[]).some(image=>image.key===file.fileKey);
                if(file.type==='image'&&remote)complete++;
              } else if(file.type==='file'&&file.parseState===1)complete++;
            }
            return JSON.stringify({ready:complete===state.expected.length&&files.length===state.expected.length,detail:'豆包已确认 '+complete+'/'+state.expected.length+' 个附件处理完成'});
          }
        """.trimIndent()
        ArenaService.KIMI -> """
          const area=document.querySelector('[data-testid="input-attachment-list"],.chat-editor-attachment-area');
          if(area) {
            const cards=Array.from(area.querySelectorAll('.file-card-container,.image-thumbnail')).filter(e=>visible(e)&&!state.baseline.has(e));
            if(cards.length>state.expected.length)return JSON.stringify({error:'Kimi 出现额外附件，未发送问题；请打开原网页检查未发送的附件'});
            let complete=0,imageIndex=0;
            for(const expected of state.expected) {
              let card;
              if(expected.mime.startsWith('image/')) {
                card=cards.filter(e=>e.matches('.image-thumbnail'))[imageIndex++];
              } else {
                const stem=expected.name.replace(/\.[^.]+${'$'}/,'');
                const ext=expected.name.split('.').pop().toLowerCase();
                const matches=cards.filter(e=>{
                  if(!e.matches('.file-card-container')||(e.querySelector('.file-card-info-name')?.textContent||'').trim()!==stem)return false;
                  const captions=Array.from(e.querySelectorAll('.file-ext')),icons=Array.from(e.querySelectorAll('img.file-card-icon'));
                  if(captions.length>1||icons.length>1)return false;
                  // FileCard always exposes meta.ext on its icon; FAILED replaces the detail row.
                  const extensions=[...captions.map(e=>e.textContent),...icons.map(e=>e.getAttribute('alt'))].map(value=>(value||'').trim().replace(/^\./,'').toLowerCase()).filter(Boolean);
                  return extensions.length>0&&extensions.every(value=>value===ext);
                });
                if(matches.length>1)return JSON.stringify({error:expected.name+'：Kimi 附件身份重复，未发送问题'});
                card=matches[0];
              }
              if(!card) continue;
              if(card.classList.contains('error')) return JSON.stringify({error:expected.name+'：Kimi 附件上传或解析失败'});
              if(!card.classList.contains('success')) continue;
              if(expected.mime.startsWith('image/')) {
                const image=card.querySelector('img');
                const images=cards.filter(e=>e.matches('.image-thumbnail'));
                // Public Kimi upload pipeline: new PENDING/PROCESSING cards are loading;
                // only parse SUCCESS transitions them to success. Historical UNSPECIFIED is excluded.
                if(state.imageWasLoading.has(card) && image && /^https:\/\//.test(image.currentSrc||image.src||'') && images.length===state.expected.filter(f=>f.mime.startsWith('image/')).length) complete++;
              } else complete++;
            }
            return JSON.stringify({ready:complete===state.expected.length,detail:'Kimi 已确认 '+complete+'/'+state.expected.length+' 个附件解析完成'});
          }
        """.trimIndent()
        ArenaService.QWEN -> """
          const shells=Array.from(document.querySelectorAll('[data-chat-input-shell]')).filter(visible);
          const shell=shells.length===1?shells[0]:null;
          const top=shell&&shell.querySelector('[data-chat-input-top-content]');
          const cards=top?Array.from(top.querySelectorAll('[data-icon-type=qwpcicon-close2]')).map(icon=>icon.parentElement).filter(Boolean):[];
          if(cards.length) {
            if(cards.length>state.expected.length)return JSON.stringify({error:'千问出现额外附件，未发送问题；请打开原网页检查未发送的附件'});
            // Image cards receive {record}; document cards receive the record fields directly (2026-09-15).
            const entries=cards.map(card=>{const props=reactProps(card,'record')||reactProps(card,'recordId');const record=props&&(props.record&&typeof props.record==='object'?props.record:props);return {card,props,record};});
            if(entries.some(entry=>!entry.record||!entry.record.recordId))return JSON.stringify({ready:false,detail:'无法确认千问附件处理状态'});
            let complete=0;const used=new Set();
            for(const expected of state.expected) {
              const named=entries.filter(entry=>!used.has(entry)&&entry.record.fileName===expected.name);
              if(named.length>1)return JSON.stringify({error:expected.name+'：千问附件身份重复，未发送问题'});
              const entry=named[0];
              if(!entry)continue;
              used.add(entry);
              const record=entry.record,isImage=expected.mime.startsWith('image/');
              if(Number(record.fileSize)!==expected.size&&!state.selectionConfirmed)continue;
              if(record.recordStatus===-1||record.recordStatus===2||entry.props.exceeded===true||entry.props.unsupportedMixed===true)return JSON.stringify({error:expected.name+'：千问附件上传或解析失败，未发送问题'});
              if((record.recordType==='image')!==isImage)return JSON.stringify({error:expected.name+'：千问附件类型与所选文件不一致，未发送问题'});
              // recordStatus: 3 uploading, 0 parsing, 1 parse success, -1 upload failed, 2 parse failed.
              if(record.recordStatus!==1||Number(record.uploadProgress)!==100||!record.fileUrl||entry.card.querySelector('[data-icon-type=qwpcicon-loading]'))continue;
              complete++;
            }
            if(used.size<entries.length&&entries.every(entry=>entry.record.recordStatus===1))return JSON.stringify({error:'千问附件名称与所选文件不一致，未发送问题'});
            return JSON.stringify({ready:complete===state.expected.length&&cards.length===state.expected.length,detail:'千问已确认 '+complete+'/'+state.expected.length+' 个附件处理完成'});
          }
        """.trimIndent()
        ArenaService.YUANBAO -> """
          const inputCards=Array.from(document.querySelectorAll('[data-new-input-card]')).filter(visible);
          const area=inputCards.length===1?inputCards[0].querySelector('[data-input-resource-area]'):null;
          const items=area?Array.from(area.querySelectorAll('[aria-label="删除文件"]')).map(control=>control.parentElement).filter(Boolean):[];
          if(items.length) {
            if(items.length>state.expected.length)return JSON.stringify({error:'元宝出现额外附件，未发送问题；请打开原网页检查未发送的附件'});
            const entries=items.map(item=>({item,file:reactProps(item,'file')?.file}));
            if(entries.some(entry=>!entry.file||typeof entry.file!=='object'))return JSON.stringify({ready:false,detail:'无法确认元宝附件处理状态'});
            let complete=0;const used=new Set();
            for(const expected of state.expected) {
              const named=entries.filter(entry=>!used.has(entry)&&entry.file.name===expected.name);
              if(named.length>1)return JSON.stringify({error:expected.name+'：元宝附件身份重复，未发送问题'});
              const entry=named[0];
              if(!entry)continue;
              used.add(entry);
              const file=entry.file,status=String(file.status||'').toLowerCase(),isImage=expected.mime.startsWith('image/');
              if(Number(file.size)!==expected.size&&!state.selectionConfirmed)continue;
              if(['error','fail','failed','uploadfail','uploaderror','reject','rejected','timeout','abort'].includes(status))return JSON.stringify({error:expected.name+'：元宝附件上传失败，未发送问题'});
              if((file.type==='image')!==isImage)return JSON.stringify({error:expected.name+'：元宝附件类型与所选文件不一致，未发送问题'});
              // Observed: loading/progress 0 -> finish/progress 100 with a remote fileId. The send button is enabled earlier.
              if(status!=='finish'||Number(file.progress)!==100||!file.fileId)continue;
              complete++;
            }
            if(used.size<entries.length&&entries.every(entry=>String(entry.file.status).toLowerCase()==='finish'))return JSON.stringify({error:'元宝附件名称与所选文件不一致，未发送问题'});
            return JSON.stringify({ready:complete===state.expected.length&&items.length===state.expected.length,detail:'元宝已确认 '+complete+'/'+state.expected.length+' 个附件处理完成'});
          }
        """.trimIndent()
        else -> ""
    }

    fun cancel(requestId: String): String = """
        (() => {const s=window.__arenaAttachment;if(s&&s.id===${ArenaJs.quote(requestId)}){document.removeEventListener('change',s.listener,true);for(const input of s.selectionInputs||[s.selectionInput])if(input)input.removeEventListener('change',s.listener,true);if(s.pendingControl?.clickTarget)s.pendingControl.clickTarget.removeEventListener('click',s.pendingControl.clickListener,true);if(s.observer)s.observer.disconnect();delete window.__arenaAttachment;}return true;})();
    """.trimIndent()
}
