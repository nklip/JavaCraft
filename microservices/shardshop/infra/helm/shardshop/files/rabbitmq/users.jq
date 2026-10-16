# Used only to generate a runtime Secret; no credentials are rendered into Helm resources.
{
  vhosts: [{name: "shardshop"}],
  users: [
    {name: "shardshop-admin", password: $admin, tags: ["administrator"]},
    {name: "shardshop-order", password: $order, tags: []},
    {name: "shardshop-ledger", password: $ledger, tags: []}
  ],
  permissions: [
    {user: "shardshop-admin", vhost: "shardshop", configure: ".*", write: ".*", read: ".*"},
    {user: "shardshop-order", vhost: "shardshop", configure: "^$", write: "^ledger\\.commands$", read: "^order\\.ledger-results$"},
    {user: "shardshop-ledger", vhost: "shardshop", configure: "^$", write: "^order\\.saga-results$", read: "^ledger\\.record-order$"}
  ]
}
