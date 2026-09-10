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
    """.trimIndent()
}
