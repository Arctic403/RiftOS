/* Native RiftShell -> approved Core Generic Tools source/contract checks.
 * Pure JS harness, no device calls, no Node, no Android Kotlin compilation.
 */
function verifyNativeGenericToolSource(shell, core, commands) {
  const results = [];
  const check = (label, pass) => results.push({label, pass: Boolean(pass)});
  check('native tool command dispatch present',
    shell.includes('"tool" -> genericToolCommand(raw, cwd)'));
  check('tool list forwarding to selected Core', shell.includes('request.put("operation", "tool-list")'));
  check('tool call forwarding to selected Core', shell.includes('request.put("operation", "tool-call")'));
  check('request uses Core-owned selected external provider gate',
    shell.includes('RiftHostCoreComponents.externalAppConnections()') &&
    shell.includes('.appConnectionRequest(appContext, request)'));
  check('no Binder direct call in the new routing', !shell.slice(
    shell.indexOf('private fun genericToolCommand'), shell.indexOf('private fun cdCommand'))
    .includes('RiftHostExternalAppBinderV1'));
  check('Core has UI consent fallback when no trust lease',
    core.includes('approval(label, "tool.call."+key)') ||
    core.includes('if (!trusted) approval(label,"tool.call."+key)'));
  check('Core checks saved allowed operations',
    core.includes('require(name in ops(item.getJSONArray("operations")))'));
  check('Core compares signer before every invocation',
    core.includes('require(before==item.getString("signerSha256"))'));
  check('Core re-reads grant after user consent',
    core.includes('val live = read(context,key)') &&
    core.includes('require(live.toString() == item.toString())'));
  check('Core verifies signer after invocation',
    core.includes('require(identity(context,item)==before)'));
  check('shell validates approved id syntax',
    shell.includes('Regex("^[a-z][a-z0-9-]{1,39}$")'));
  check('shell rejects arbitrary request paths',shell.includes(
    'Regex("^/D:/(Workspace|Documents|Projects)/[A-Za-z0-9_./-]+\\\\.json$")'));
  check('shell rejects path traversal and symlink',
    shell.includes('it == "." || it == ".."') &&
    shell.includes('isSymbolicLink(input.toPath())') &&
    shell.includes('approvedRoot.canonicalPath + File.separator'));
  check('shell request bounded at provider and Core limit',
    shell.includes('2..16_384') && shell.includes('<= 24_576'));
  check('shell requires exactly one provider operation field',
    shell.includes('provider.has("operation") != provider.has("op")'));
  check('user has ready-made RiftBench commands',
    ['status','project-summary','list-cubes','selftest','export-info']
      .every(x=>Boolean(commands[x+".json"])));
  for (const [name, text] of Object.entries(commands)) {
    const obj=JSON.parse(text);
    check(name+' provider schema valid', obj.schema==='rift.riftbench-tool/1');
    check(name+' operation bounded', /^[A-Za-z][A-Za-z0-9._-]{0,47}$/.test(obj.operation));
  }

  // Test the command argument grammar in a JS representation.
  // The Kotlin implementation requires the entire provider JSON, not
  // provider-specific magic defaults that could bypass Core approval.
  const parse=(raw)=>{
    const parts=raw.trim().split(/\s+/);
    const head=parts.slice(0,3);
    if(head[0]!=='tool')return false;
    if(head[1]==='list')return parts.length===2;
    if(head[1]!=='call')return false;
    const id=head[2];
    if(!/^[a-z][a-z0-9-]{1,39}$/.test(id))return false;
    const payload=raw.trim().split(/\s+/,3).length===3
      ? raw.trim().slice(raw.trim().indexOf(id)+id.length).trim() : '';
    if(payload.startsWith('{')){
      try{const j=JSON.parse(payload);return typeof j.schema==='string' &&
        j.schema.length>0 && (('operation' in j)!==('op' in j)) &&
        new TextEncoder().encode(payload).length<=16384;}catch(_){return false;}
    }
    return payload.length>=20 && payload.length<=240 &&
      /^\/D:\/(Workspace|Documents|Projects)\/[A-Za-z0-9_./-]+\.json$/.test(payload) &&
      !payload.includes('//') && !payload.split('/').some(x=>x==='.'||x==='..');
  };
  const yes=[
    'tool list',
    'tool call riftbench-bridge /D:/Workspace/RiftBenchBridge/commands/project-summary.json',
    'tool call riftbench-bridge {"schema":"rift.riftbench-tool/1","operation":"status"}'
  ], no=[
    'tool attach riftbench-bridge',
    'tool call riftbench-bridge /D:/Workspace/../Secrets/credentials.json',
    'tool call riftbench-bridge /C:/System/request.json',
    'tool call BAD_ID /D:/Workspace/request.json',
    'tool call riftbench-bridge {"schema":"x","operation":"status","op":"bad"}',
    'tool call riftbench-bridge {"schema":"x"}'
  ];
  yes.forEach(x=>check('accept '+x.slice(0,80),parse(x)));
  no.forEach(x=>check('reject '+x.slice(0,80),!parse(x)));
  // v0.6.22 Core owns temporary, explicitly consented trust; host is only
  // a narrow typed command front end. No direct Binder or automatic trust.
  check('trust start CLI reaches selected Core',shell.includes('"tool-session-start"') &&
    shell.includes('request.put("operation","tool-session-start")'));
  check('trust status CLI exists',shell.includes('request.put("operation","tool-session-status")'));
  check('trust stop CLI exists',shell.includes('request.put("operation","tool-session-stop")'));
  check('trust duration restricted to 1..15 minutes',
    shell.includes('minutes in 1..15'));
  check('trust operation count capped at eight',
    shell.includes('granted.size in 1..8'));
  check('trust scope names unique and validated',
    shell.includes('granted.size==granted.toSet().size'));
  check('trust still forwards through Core',
    shell.includes('RiftHostCoreComponents.externalAppConnections()'));
  return {passed:results.filter(x=>x.pass).length,failed:results.filter(x=>!x.pass).length,
    tests:results.length,failures:results.filter(x=>!x.pass),all:results};
}
