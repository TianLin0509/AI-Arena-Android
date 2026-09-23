package com.tianlin.aiarena

/** Build mounted trees with both child/sibling and return edges, like React's committed tree. */
internal object ArenaReactFixture {
    val script = """
        window.fixtureNode=(props,parent,stateNode=null)=>{
          const fiber={memoizedProps:props,return:parent,stateNode,child:null,sibling:null};
          if(parent){if(!parent.child)parent.child=fiber;else{let previous=parent.child;while(previous.sibling)previous=previous.sibling;previous.sibling=fiber;}}
          return fiber;
        };
        window.fixtureFiber=(element,props,parent)=>{
          const fiber=fixtureNode(props,parent,element);
          element['__reactFiber${'$'}fixture']=fiber;return fiber;
        };
        window.fixtureDoubaoMessage=(row,text)=>{
          const boundary=row.querySelector('[data-send-message-boundary]');
          const root={tag:3,stateNode:{},child:null};root.stateNode.current=root;
          const host=fixtureFiber(row,{},root);
          const owner=fixtureNode({message:{message_id:boundary.getAttribute('data-message-id'),
            content_blocks:[{block_type:10000,is_deleted:false,content_obj:{text}}]}},host);
          fixtureFiber(boundary,{},owner);
        };
    """.trimIndent()
}
