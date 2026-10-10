// Regenerate compatibility fixtures from a Memos checkout; no Go code is shipped in Memoize.
package main

import (
    "encoding/json"
    "go/ast"
    "go/parser"
    "go/token"
    "os"
    "strconv"
    "strings"
)

type fixture struct { Name string `json:"name"`; Content string `json:"content"`; Expected []string `json:"expected"` }
func value(e ast.Expr) string {
    switch e := e.(type) {
    case *ast.BasicLit:
        s, err := strconv.Unquote(e.Value); if err != nil { panic(err) }; return s
    case *ast.BinaryExpr: return value(e.X) + value(e.Y)
    case *ast.CallExpr:
        count, err := strconv.Atoi(e.Args[1].(*ast.BasicLit).Value); if err != nil { panic(err) }; return strings.Repeat(value(e.Args[0]),count)
    }; panic("unsupported fixture expression")
}
func main() {
    file, err := parser.ParseFile(token.NewFileSet(),os.Args[len(os.Args)-1],nil,0); if err != nil { panic(err) }
    var output []fixture
    for _, declaration := range file.Decls {
        f, ok := declaration.(*ast.FuncDecl); if !ok || f.Name.Name != "TestExtractTagsMemosTagV1" { continue }
        for _, statement := range f.Body.List {
            assignment, ok := statement.(*ast.AssignStmt); if !ok || assignment.Lhs[0].(*ast.Ident).Name != "tests" { continue }
            for _, element := range assignment.Rhs[0].(*ast.CompositeLit).Elts {
                item := fixture{Expected:[]string{}}
                for _, field := range element.(*ast.CompositeLit).Elts {
                    kv := field.(*ast.KeyValueExpr)
                    switch kv.Key.(*ast.Ident).Name {
                    case "name": item.Name = value(kv.Value)
                    case "content": item.Content = value(kv.Value)
                    case "expected": for _, expected := range kv.Value.(*ast.CompositeLit).Elts { item.Expected = append(item.Expected,value(expected)) }
                    }
                }; output = append(output,item)
            }
        }
    }
    encoder := json.NewEncoder(os.Stdout); encoder.SetIndent("","  "); if err := encoder.Encode(output); err != nil { panic(err) }
}
