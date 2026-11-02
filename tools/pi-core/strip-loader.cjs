const { stripTypeScriptTypes } = require('node:module');
module.exports = function(source) { return stripTypeScriptTypes(source, { mode: 'strip' }); };
