require "stringio.so"

if "test".frozen? && !"test".equal?("test") # Ruby 3.4+ chilled strings
  class StringIO
    alias_method :_initialize, :initialize
    private :_initialize

    def initialize(*args, **kwargs)
      string = args.first
      if string && string.frozen?
        begin
          string << "" # Eagerly defrost the string
        rescue FrozenError
        end
      end
      _initialize(*args, **kwargs)
    end
  end
end
