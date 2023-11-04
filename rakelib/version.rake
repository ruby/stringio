class << (helper = Bundler::GemHelper.instance)
  C_SOURCE_PATH = "ext/stringio/stringio.c"
  JAVA_SOURCE_PATH = "ext/java/org/jruby/ext/stringio/StringIO.java"
  def update_source_version
    v = version.to_s
    [C_SOURCE_PATH, JAVA_SOURCE_PATH].each do |path|
      source = File.read(path)
      if source.sub!(/^\s*STRINGIO_VERSION\s*=\s*"\K.*(?=")/) {break if $& == v; v}
        File.write(path, source)
      end
    end
  end

  def commit_bump
    sh([*%w[git commit -m], "Development of #{gemspec.version} started.",
        C_SOURCE_PATH,
        JAVA_SOURCE_PATH])
  end

  def version=(v)
    unless v == version
      unless already_tagged?
        ensure_news("Previous", version)
        abort "Previous version #{version} is not tagged yet"
      end
    end
    gemspec.version = v
    update_source_version
    commit_bump
  end

  def tag_version
    ensure_news("New", version)
    super
  end

  def ensure_news(that, version)
    news = File.read(File.join(__dir__, "../NEWS.md"))
    unless /^## +#{Regexp.quote(version.to_s)} -/ =~ news
      abort "#{that} version #{version} is not mentioned in NEWS.md"
    end
  end
end

major, minor, teeny = helper.gemspec.version.segments

desc "Bump teeny version"
task "bump:teeny" do
  helper.version = Gem::Version.new("#{major}.#{minor}.#{teeny+1}")
end

desc "Bump minor version"
task "bump:minor" do
  helper.version = Gem::Version.new("#{major}.#{minor+1}.0")
end

desc "Bump major version"
task "bump:major" do
  helper.version = Gem::Version.new("#{major+1}.0.0")
end

desc "Bump teeny version"
task "bump" => "bump:teeny"

task "tag" do
  helper.tag_version
end
