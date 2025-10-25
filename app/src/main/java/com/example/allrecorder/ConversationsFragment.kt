package com.example.allrecorder

import android.content.Intent // 👈 Add this import
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.allrecorder.databinding.FragmentConversationsBinding

class ConversationsFragment : Fragment() {

    private var _binding: FragmentConversationsBinding? = null
    private val binding get() = _binding!!
    private lateinit var conversationDao: ConversationDao
    private lateinit var conversationAdapter: ConversationAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConversationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        conversationDao = AppDatabase.getDatabase(requireContext()).conversationDao()

        // --- APPLY THIS CHANGE ---
        // Modify the adapter's click listener
        conversationAdapter = ConversationAdapter { conversation ->
            // Create an intent to open ConversationDetailActivity
            val intent = Intent(requireActivity(), ConversationDetailActivity::class.java)

            // Pass the ID of the clicked conversation to the next screen
            intent.putExtra(ConversationDetailActivity.EXTRA_CONVERSATION_ID, conversation.id)

            startActivity(intent)
        }
        // --- END OF CHANGE ---

        binding.conversationsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = conversationAdapter
        }

        conversationDao.getAllConversations().observe(viewLifecycleOwner) { conversations ->
            conversations?.let {
                conversationAdapter.submitList(it)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}