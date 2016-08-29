#!/usr/bin/perl -w

use strict;

my $reduce=$ARGV[0];    # number of reduce tasks
my $replicas=$ARGV[1];  # number of replicas
my @table;

GetDigests();
my %hashes=GetMajority();
GetReduceOutput();
ValidateHash(\%hashes);
exit(0);

sub GetDigests{
 for (my $number = 0; $number < $reduce; $number++) {
     for (my $count = 0; $count < $replicas; $count++) {
	 my $nr=sprintf ('%05d', $number);
	 my $cmd="bin/hadoop dfs -cat /user/xubuntu/gutenberg-output/part-r-".$nr."_".$count."_sha";
         print "Obtaining digest file $cmd\n";
         my $res= `$cmd`;
         @table[$number*$replicas+$count] = trim($res);
     }
 }
}

sub GetReduceOutput{
 `rm -rf ~/result`;
 `mkdir ~/result`;
 for (my $number = 0; $number < $reduce; $number++) {
     for (my $count = 0; $count < $replicas; $count++) {
	 my $nr=sprintf ('%05d', $number);
	 my $cmd="bin/hadoop dfs -copyToLocal /user/xubuntu/gutenberg-output/part-r-".$nr."_".$count." ~/result/";
         print "Copying file $cmd\n";
	`$cmd`
     }
 }
}

sub ValidateHash{
         my ($hashlist) = @_;
	 for (my $number = 0; $number < $reduce; $number++) {
	     for (my $count = 0; $count < $replicas; $count++) {
		my $nr=sprintf ('%05d', $number);
		my $cmd="sha1sum ~/result/part-r-".$nr."_".$count." | awk '{print \$1}'";
		my $result=`$cmd`;
		$result = trim($result);

 	        foreach my $key (keys %$hashlist) {
		    if($key eq trim($result)) {
  		       print "~/result/part-r-".$nr."_".$count." is a valid result\n";
		   }
 	        }
	     }
	 }
}

sub GetMajority{
	my %account;
	my %results;
	for (my $number = 0; $number < $reduce; $number++) {
	    for (my $count = 0; $count < $replicas; $count++) {
		 my $idx=$number*$replicas+$count;
		 my $key=$table[$idx];

		 if (exists $account{$key}){
		      $account{$key}+=1;
		 } else {
		      $account{$key}=1;
		 }
	    }

	    my $res=SortByValue(\%account);
 	    foreach my $key (keys %$res) {
		$results{$key}=$res->{$key};
 	    }
	}

	return %results;
# 	    foreach my $key (keys %account) {
# 		  print "$key\n";
# 		  print "$account{$key}\n";
# 	    }
# 	    account = reverse sort {$account->{$a} cmp $account->{$b}} keys %$account;
# 
# 	    foreach my $key (keys %account) {
# 		  print "$key\n";
# 		  print "$account{$key}\n";
# 	    }
}

sub SortByValue {
   my ($account) = @_;
 	#%account = reverse sort {$account->{$a} cmp $account->{$b}} keys %$account;
   foreach my $key (sort {$account->{$a} cmp $account->{$b}} keys %$account ) {
  	   return {$key => $account->{$key}};
   }
}

sub trim($) {
	my $string = shift;
	$string =~ s/^\s+//;
	$string =~ s/\s+$//;
	return $string;
}

