# StringIO

![ubuntu](https://github.com/ruby/stringio/workflows/ubuntu/badge.svg?branch=master&event=push)
![macos](https://github.com/ruby/stringio/workflows/macos/badge.svg?branch=master&event=push)
![windows](https://github.com/ruby/stringio/workflows/windows/badge.svg?branch=master&event=push)

Pseudo `IO` class from/to `String`.

This library is based on MoonWolf version written in Ruby.  Thanks a lot.

## Differences to `IO`

* `fileno` raises `NotImplementedError`.
* encoding conversion is not implemented, and ignored silently.

## Installation

Add this line to your application's Gemfile:

```ruby
gem 'stringio'
```

And then execute:

    $ bundle

Or install it yourself as:

    $ gem install stringio

## Development

After checking out the repo, run `bin/setup` to install dependencies. Then, run `rake test` to run the tests. You can also run `bin/console` for an interactive prompt that will allow you to experiment.

To install this gem onto your local machine, run `bundle exec rake install`. To release a new version, update the version number in `version.rb`, and then run `bundle exec rake release`, which will create a git tag for the version, push git commits and tags, and push the `.gem` file to [rubygems.org](https://rubygems.org).

## Contributing

Bug reports and pull requests are welcome on GitHub at https://github.com/ruby/stringio.

## License

The gem is available as open source under the terms of the [2-Clause BSD License](https://opensource.org/licenses/BSD-2-Clause).
