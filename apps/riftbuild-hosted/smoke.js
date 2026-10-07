(() => {
  function check(v,m){if(!v)throw new Error(m);}
  const boot=JSON.parse(globalThis.riftRappMain(JSON.stringify({
    stateBase64:"",
    event:{kind:0,targetId:0,arg0:0,arg1:0,arg2:0,arg3:0,text:"",bytesBase64:""}
  })));
  check(boot.schema==="riftos-app-output-json/1","boot schema");
  check(boot.frame&&Array.isArray(boot.frame.nodes)&&boot.frame.nodes.length>=13,"boot frame");
  check(boot.frame.nodes.some(n=>n.id===119&&n.text==="Compile"),"compile control");
  check(typeof boot.stateBase64==="string"&&boot.stateBase64.length>0,"boot state");

  const edited=JSON.parse(globalThis.riftRappMain(JSON.stringify({
    stateBase64:boot.stateBase64,
    event:{kind:7,targetId:100,arg0:0,arg1:0,arg2:0,arg3:0,text:"/D:/Workspace/HostedProof",bytesBase64:""}
  })));
  const input=edited.frame.nodes.find(n=>n.id===100);
  const activeBefore=edited.frame.nodes.find(n=>n.id===4);
  check(input&&input.text==="/D:/Workspace/HostedProof","project draft state");
  check(activeBefore&&activeBefore.text==="Active project: /D:/Workspace","typing must not promote project");

  const confirmed=JSON.parse(globalThis.riftRappMain(JSON.stringify({
    stateBase64:edited.stateBase64,
    event:{kind:1,targetId:101,arg0:0,arg1:0,arg2:0,arg3:0,text:"",bytesBase64:""}
  })));
  const activeAfter=confirmed.frame.nodes.find(n=>n.id===4);
  check(activeAfter&&activeAfter.text==="Active project: /D:/Workspace/HostedProof","set project action");

  const pre=JSON.parse(globalThis.riftRappMain(JSON.stringify({
    stateBase64:confirmed.stateBase64,
    event:{kind:1,targetId:120,arg0:0,arg1:0,arg2:0,arg3:0,text:"",bytesBase64:""}
  })));
  const preStatus=pre.frame.nodes.find(n=>n.id===3);
  check(!pre.effect,"preflight must not bypass compile");
  check(preStatus&&preStatus.text.includes("run successful Compile first"),"preflight requires compile");

  const compile=JSON.parse(globalThis.riftRappMain(JSON.stringify({
    stateBase64:confirmed.stateBase64,
    event:{kind:1,targetId:119,arg0:0,arg1:0,arg2:0,arg3:0,text:"",bytesBase64:""}
  })));
  check(compile.effect&&compile.effect.capability==="fs.read","compile recipe capability");
  check(compile.effect.operation==="readText","compile recipe operation");
  check(compile.effect.text==="/D:/Workspace/HostedProof/rift-hosted.json","compile recipe path");
  print("ok - RiftBuild Hosted RAPP boot/state/compile-gate protocol");
})();