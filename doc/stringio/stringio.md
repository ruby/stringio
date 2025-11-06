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

#### Summary

|            Mode            | Truncate? |   Read   | Read Pos |  Write   | Write Pos |
|:--------------------------:|:---------:|:--------:|:--------:|:--------:|:---------:|
|  <tt>'r'</tt>: read-only   |    No     | Anywhere |    0     |  Error   |     -     |
|  <tt>'w'</tt>: write-only  |    Yes    |  Error   |    -     | Anywhere |     0     |
| <tt>'a'</tt>: append-only  |    No     |  Error   |    -     | End only |    End    |
| <tt>'r+'</tt>: read/write  |    No     | Anywhere |    0     | Anywhere |     0     |
| <tt>'w+'</tt>: read-write  |    Yes    | Anywhere |    0     | Anywhere |     0     |
| <tt>'a+'</tt>: read/append |    No     | Anywhere |    0     | End only |    End    |

#### `'r'`: Read-Only

Initial state:

```ruby
strio = StringIO.new(TEXT, 'r')
strio.pos # => 0                   # Beginning of stream.
strio.string.size == 0 # => false  # Not truncated.
```

May be written anywhere; see #rewind, #pos=, #seek:

```ruby
strio.gets # => "First line\n"
strio.gets # => "Second line\n"

strio.rewind
strio.gets # => "First line\n"

strio.pos = 1
strio.gets # => "irst line\n"

strio.seek(1, IO::SEEK_CUR)
strio.gets # => "econd line\n"
```

May not be written:

```ruby
strio.write('foo')  # Raises IOError: not opened for writing
```

#### `'w'`: Write-Only (Initial Truncate)

Initial state:

```ruby
strio = StringIO.new('foo', 'w')
strio.pos    # => 0   # Beginning of stream.
strio.string # => ""  # Initially truncated.
```

May be written anywhere (even past end-of-stream); see #rewind, #pos=, #seek:

```ruby
strio.write('foobar')
strio.string # => "foobar"

strio.rewind
strio.write('FOO')
strio.string # => "FOObar"

strio.pos = 3
strio.write('BAR')
strio.string # => "FOOBAR"

strio.seek(1, IO::SEEK_CUR)
strio.write('baz')
strio.string # => "FOOBAR\u0000baz"  # Null-padded.

strio.pos = 20
strio.write('bat')
strio.string # => "FOOBAR\u0000baz\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000bat"
```

May not be read:

```ruby
strio.read  # Raises IOError: not opened for reading
```

#### `'a'`: Append-Only

Initial state:

```ruby
strio = StringIO.new('foo', 'a')
strio.pos # => 0         # Beginning-of-stream.
strio.string # => "foo"  # Not truncated.
```

May be written only at the end; #rewind, #pos=, #seek do not affect writing:

```ruby
strio.write('bar')
strio.string # => "foobar"
strio.write('baz')
strio.string # => "foobarbaz"

strio.rewind
strio.pos = 400
strio.seek(1, IO::SEEK_CUR)
strio.write('bat')
strio.string # => "foobarbazbat"
```

May not be read:

```ruby
strio.gets  # Raises IOError: not opened for reading
```

#### `'r+'`: Read/Write

Initial state:

```ruby
strio = StringIO.new('foobar', 'r+')
strio.pos    # => 0         # Beginning-of-stream.
strio.string # => "foobar"  # Not truncated.
```

May be read or written anywhere (even past end-of-stream); see #rewind, #pos=, #seek:

```ruby
strio.gets # => "foobar"

strio.rewind
strio.gets # => "foobar"

strio.pos = 3
strio.gets # => "bar"

strio.rewind
strio.write('FOO')
strio.string # => "FOObar"

strio.pos = 3
strio.write('BAR')
strio.string # => "FOOBAR"

strio.pos = 9
strio.write('baz')
strio.string # => "FOOBAR\u0000\u0000\u0000baz"
```

#### `'w+'`: Read/Write (Initial Truncate)

Initial state:

```ruby
strio = StringIO.new('foo', 'w+')
strio.pos # => 0      # Beginning-of-stream.
strio.string # => ""  # Truncated.
```

May be read or written anywhere (even past end-of-stream); see #rewind, #pos=, #seek:


```ruby
strio.write('bar')
strio.string # => "bar"

strio.write('foo')
strio.string # => "barfoo"

strio.rewind
strio.write('FOO')
strio.string # => "FOOfoo"

strio.write('BAR')
strio.string # => "FOOBAR"

strio.pos = 10
strio.write('baz')
strio.string # => "FOOBAR\u0000\u0000\u0000\u0000baz"
```

#### `'a+'`: Read/Append

Initial state:

```ruby
strio = StringIO.new('foo', 'a+')
strio.pos# => 0          # Beginning-of-stream.
strio.string # => "foo"  # Not truncated.
```

May be written only at the end; #rewind, #pos=, #seek do not affect writing:

```ruby
strio.write('bar')
strio.string # => "foobar"
strio.write('baz')
strio.string # => "foobarbaz"

strio.rewind
strio.pos = 400
strio.seek(1, IO::SEEK_CUR)
strio.write('bat')
strio.string # => "foobarbazbat"
```

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
