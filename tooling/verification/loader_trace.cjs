const fs = require('node:fs');
const moduleApi = require('node:module');
const original = moduleApi.registerHooks;
moduleApi.registerHooks = function (hooks) {
  if (!hooks.resolve) return original(hooks);
  return original({...hooks, resolve(specifier, context, next) {
    const result = hooks.resolve(specifier, context, next);
    fs.appendFileSync(process.env.COMPOSEDOC_LOADER_TRACE, JSON.stringify({specifier, parentURL: context.parentURL,
      conditions: context.conditions, url: result.url}) + '\n');
    return result;
  }});
};
moduleApi.syncBuiltinESMExports();
