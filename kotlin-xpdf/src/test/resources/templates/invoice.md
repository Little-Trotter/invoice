<!DOCTYPE html>
<html>
  <head>
    <meta charset="utf-8" />
    <title>Invoice</title>
  </head>
  <body>
    <h1>Invoice: {{invoice.number}}</h1>
    <p>Date: {{invoice.date}}</p>
    <ul>
      {{#invoice.items}}
      <li>{{description}} — {{qty}} × {{lineTotal}}</li>
      {{/invoice.items}}
    </ul>
    <p>Net: {{invoice.netTotal}}</p>
  </body>
</html>
