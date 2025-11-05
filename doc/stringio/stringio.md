\Class \StringIO supports accessing a string as a stream,
similar in some ways to [class IO][class io].

You can create a \StingIO instance using:

- StringIO.new(string): returns a new \StringIO object containing the given string.
- StringIO.open(string): passes a new \StringIO object to the given block.

Like an \IO stream, a \StringIO stream has certain properties:

- **Read/write mode**: whether the stream may be read, written, appended to, etc.;
  see [Read/Write Mode][read/write mode].
- **Data mode**: text-only or binary;
  see [Data Mode][data mode].
- **Encodings**: internal and external encodings;
  see [Encodings][encodings].
- **Position**: where in the stream the next read or write is to occur;
  see [Position][position].
- **Line number**: a special, line-oriented, "position" (different from the position mentioned above);
  see [Line Number][line number].
- **Open/closed**: whether the stream is open or closed, for reading or writing.
  see [Open/Closed Streams][open/closed streams].
- **End-of-stream**: whether the position is at the end of the stream;
  see [End-of-Stream][end-of-stream].

## About the Examples

Examples on this page assume that \StringIO has been required:

```
require 'stringio'
```

And that these constants have been defined:

```
TEXT = <<EOT
First line
Second line

Fourth line
Fifth line
EOT

RUSSIAN = 'тест'
DATA = "\u9990\u9991\u9992\u9993\u9994"
```

## Stream Properties

### Read/Write Mode

### Data Mode

### Encodings

### Position

### Line Number

### Open/Closed Streams

### End-of-Stream

## Basic Stream \IO

### Reading

You can read immediately from the stream using these instance methods:

- #getbyte: reads and returns the next byte.
- #getc: reads and returns the next character.
- #gets: reads and returns the next line.
- #read: reads and returns the remaining data in the stream.
- #readlines: reads the remaining data the stream and returns an array of its lines.

You can iterate over the stream using these instance methods:

- #each_byte: reads each remaining byte, passing it to the block.
- #each_char: reads each remaining character, passing it to the block.
- #each_codepoint: reads each remaining codepoint, passing it to the block.
- #each_line: reads each remaining line, passing it to the block

This instance method is useful in a multi-threaded application:

- #pread.

### Writing

You can write to the stream, advancing the position, using these instance methods:

- #putc: write the given character.
- #write: write the given strings.

You can "unshift" to the stream using these instance methods;
each writes at the current position, without advancing the position,
so that the written data is next to be read.

- #ungetbyte: unshift the given byte.
- #ungetc.: unshift the given character.

This instance method truncates the stream to the given size:

- #truncate.

## Line \IO

## Character \IO

## Byte \IO

## Codepoint \IO


[class io]: https://docs.ruby-lang.org/en/master/IO.html

[data mode]:           rdoc-ref:StringIO@Data+Mode
[encodings]:           rdoc-ref:StringIO@Encodings
[end-of-stream]:       rdoc-ref:StringIO@End-of-Stream
[line number]:         rdoc-ref:StringIO@Line+Number
[open/closed streams]: rdoc-ref:StringIO@Open-2FClosed+Streams
[position]:            rdoc-ref:StringIO@Position
[read/write mode]:     rdoc-ref:StringIO@Read-2FWrite+Mode
