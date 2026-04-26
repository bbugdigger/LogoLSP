grammar Logo;

options { caseInsensitive = true; }

// ============================================================================
// PARSER
//
// Dialect notes (deliberate restrictions to keep the grammar context-free):
//   * Nested call results as arguments require parentheses:
//       forward (sum 1 2)        OK
//       forward sum 1 2          NOT supported (ambiguous arity)
//   * Statements are separated by whitespace; no explicit terminator.
//   * Bracket lists `[ ... ]` may contain either statements (for instruction
//     lists like `repeat 4 [...]`) or atoms (for data lists like `[1 2 3]`).
//     The grammar accepts both; semantic context determines interpretation.
// ============================================================================

program : statement* EOF ;

statement
    : procedureDef
    | ifStmt
    | ifelseStmt
    | repeatStmt
    | makeStmt
    | localStmt
    | outputStmt
    | stopStmt
    | call
    ;

procedureDef : TO identifier parameter* body END ;
parameter    : COLON identifier ;
body         : statement* ;

ifStmt     : IF expression listLiteral ;
ifelseStmt : IFELSE expression listLiteral listLiteral ;
repeatStmt : REPEAT expression listLiteral ;
makeStmt   : MAKE (quotedWord | varRef) expression ;
localStmt  : LOCAL quotedWord+ ;
outputStmt : (OUTPUT | OP) expression ;
stopStmt   : STOP ;

call : identifier atom* ;

expression
    : MINUS expression                                       # negExpr
    | atom                                                   # atomExpr
    | expression op=(STAR | SLASH) expression                # mulExpr
    | expression op=(PLUS | MINUS) expression                # addExpr
    | expression op=(LE | GE | NE | LT | GT | EQ) expression # cmpExpr
    ;

atom
    : varRef
    | quotedWord
    | NUMBER
    | listLiteral
    | LPAREN parenInner RPAREN
    ;

// Parenthesised content may be either a call (whose return value is the
// expression) or a plain expression.
parenInner : call | expression ;

listLiteral : LBRACKET (statement | atom)* RBRACKET ;

varRef     : COLON identifier ;
quotedWord : QUOTE identifier ;

// Keywords are also accepted in identifier positions (variable refs,
// parameter names, procedure names) so users aren't blocked from naming
// `:if` or defining `to to ... end` in pathological cases.
identifier
    : ID
    | TO | END | IF | IFELSE | REPEAT | MAKE | LOCAL | OUTPUT | OP | STOP
    ;

// ============================================================================
// LEXER
// ============================================================================

TO     : 'to' ;
END    : 'end' ;
IF     : 'if' ;
IFELSE : 'ifelse' ;
REPEAT : 'repeat' ;
MAKE   : 'make' ;
LOCAL  : 'local' ;
OUTPUT : 'output' ;
OP     : 'op' ;
STOP   : 'stop' ;

LPAREN   : '(' ;
RPAREN   : ')' ;
LBRACKET : '[' ;
RBRACKET : ']' ;
COLON    : ':' ;
QUOTE    : '"' ;

LE : '<=' ;
GE : '>=' ;
NE : '<>' ;
LT : '<' ;
GT : '>' ;
EQ : '=' ;

PLUS  : '+' ;
MINUS : '-' ;
STAR  : '*' ;
SLASH : '/' ;

NUMBER : [0-9]+ ('.' [0-9]+)? ;
ID     : [a-z_] [a-z_0-9.?!]* ;

// Comments go on the hidden channel so the semantic-tokens provider can
// read them later for highlighting; the parser ignores them.
COMMENT : ';' ~[\r\n]* -> channel(HIDDEN) ;
WS      : [ \t\r\n]+   -> skip ;
