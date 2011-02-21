class Candidate < AvroRecord
  attr_accessor :new_record

  def self.human_name
    "Candidate"
  end

  def self.find(email)
    candidate = Candidate.find_candidate(email)
    candidate.present? ? candidate.first.first : nil
  end

  def self.waiting(field)
    candidates = Candidate.find_waiting(field)
    candidates.present? ? candidates.collect{ |c| c.first } : []
  end

  def self.top_rated(field)
    candidates = Candidate.find_top_rated(field)
    candidates.present? ? candidates.collect{ |c| c.first } : []
  end

  def self.escape(str)
    str.gsub(/([^ a-zA-Z0-9_-]+)/) do
      '%' + $1.unpack('H2' * $1.bytesize).join('%').upcase
    end.tr(' ', '+')
  end

  def self.unescape(str)
    CGI.unescape(str)
  end

  def to_param
    email.present? ? Candidate.escape(email) : nil
  end
  
  def initialize(params={})
    super(params)
    new_record = true
  end
  
  def new_record?
    new_record ||= true
    new_record
  end

  def id
    email
  end

  def interviews
    interviews = Interview.find_interviews_for_candidate(email)
    interviews.present? ? interviews.collect{ |i| i.first } : []
  end
end